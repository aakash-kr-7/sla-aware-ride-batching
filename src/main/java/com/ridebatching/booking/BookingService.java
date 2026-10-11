package com.ridebatching.booking;

import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.BookingResponse;
import com.ridebatching.booking.dto.GeoPointDto;
import com.ridebatching.booking.exception.UnserviceableZoneException;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.trip.Trip;
import com.ridebatching.trip.TripRepository;
import com.ridebatching.zone.ServiceabilityService;
import com.ridebatching.zone.ServiceableZone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.ridebatching.booking.exception.BookingNotFoundException;
import com.ridebatching.batching.BatchingWindow;
import com.ridebatching.batching.BatchingService;
import com.ridebatching.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final EmployeeRepository employeeRepository;
    private final ServiceabilityService serviceabilityService;
    private final DeduplicationKeyGenerator deduplicationKeyGenerator;
    private final AppProperties appProperties;
    private final TripRepository tripRepository;
    private final BatchingService batchingService;
    private final MeterRegistry meterRegistry;
    private final com.ridebatching.dispatch.DispatchService dispatchService;
    private final com.ridebatching.trip.TripRouteTiming routeTiming;
    private final com.ridebatching.escalation.BookingSlaEscalations escalations;

    public BookingService(BookingRepository bookingRepository,
                          EmployeeRepository employeeRepository,
                          ServiceabilityService serviceabilityService,
                          DeduplicationKeyGenerator deduplicationKeyGenerator,
                          AppProperties appProperties,
                          TripRepository tripRepository,
                          @org.springframework.context.annotation.Lazy BatchingService batchingService,
                          MeterRegistry meterRegistry,
                          com.ridebatching.dispatch.DispatchService dispatchService,
                          com.ridebatching.trip.TripRouteTiming routeTiming,
                          com.ridebatching.escalation.BookingSlaEscalations escalations) {
        this.bookingRepository = bookingRepository;
        this.employeeRepository = employeeRepository;
        this.serviceabilityService = serviceabilityService;
        this.deduplicationKeyGenerator = deduplicationKeyGenerator;
        this.appProperties = appProperties;
        this.tripRepository = tripRepository;
        this.batchingService = batchingService;
        this.meterRegistry = meterRegistry;
        this.dispatchService = dispatchService;
        this.routeTiming = routeTiming;
        this.escalations = escalations;
    }

    @Transactional
    public BookingResponse createBooking(UUID employeeId, BookingRequest request) {
        meterRegistry.counter("bookings.requests").increment();

        GeoPoint pickup = new GeoPoint(request.pickup().latitude(), request.pickup().longitude());
        GeoPoint dropoff = new GeoPoint(request.dropoff().latitude(), request.dropoff().longitude());

        Optional<ServiceableZone> zone = serviceabilityService.findZoneFor(pickup);
        if (zone.isEmpty()) {
            meterRegistry.counter("bookings.serviceability_rejections").increment();
            log.warn("Booking rejected as non-serviceable: pickup location outside active zones");
            throw new UnserviceableZoneException(
                String.format("Pickup location [%.4f, %.4f] is outside all active serviceable zones.",
                    pickup.latitude(), pickup.longitude())
            );
        }

        Employee employee = employeeRepository.findById(employeeId)
            .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        Instant now = Instant.now();
        Duration windowDuration = Duration.ofMinutes(appProperties.batching().windowDurationMinutes());
        Instant slotTimestamp = BatchingWindow.forTime(now, windowDuration).start();
        Instant slaDeadline = now.plus(Duration.ofMinutes(appProperties.sla().pickupSlaMinutes()));

        String dedupKey = deduplicationKeyGenerator.generateKey(employeeId, pickup, dropoff, slotTimestamp);

        Booking booking = new Booking(
            UUID.randomUUID(),
            employee,
            pickup,
            dropoff,
            slotTimestamp,
            BookingStatus.PENDING,
            slaDeadline,
            now,
            dedupKey
        );

        if (bookingRepository.insertIfAbsent(booking) == 0) {
            meterRegistry.counter("bookings.deduplication_hits").increment();
            log.info("Deduplication hit for employee dedup key");
            return bookingRepository.findByDeduplicationKeyAndStatusNot(dedupKey, BookingStatus.CANCELLED)
                .map(this::mapToResponse)
                .orElseThrow(() -> new com.ridebatching.api.DomainConflictException("Booking changed during duplicate submission"));
        }

        Booking saved = bookingRepository.findById(booking.getId()).orElseThrow();
        batchingService.attemptLateInsertion(saved);
        return bookingRepository.findById(saved.getId()).map(this::mapToResponse).orElseThrow();
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID id) {
        return bookingRepository.findById(id)
            .map(this::mapToResponse)
            .orElseThrow(() -> new BookingNotFoundException("Booking not found with id: " + id));
    }

    @Transactional
    public BookingResponse cancelBooking(UUID employeeId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
            .orElseThrow(() -> new BookingNotFoundException("Booking not found with id: " + bookingId));
            
        if (!booking.getEmployee().getId().equals(employeeId)) {
            throw new org.springframework.security.access.AccessDeniedException("Booking does not belong to the requested employee");
        }
        
        if (booking.getStatus() == BookingStatus.CANCELLED) return mapToResponse(booking);
        
        if (booking.getTripId() != null) {
            Trip trip = tripRepository.findByIdForUpdate(booking.getTripId())
                    .orElseThrow(() -> new IllegalStateException("Trip not found for booking"));
            trip.requireBeforeExecution();
            booking.cancel();
            trip.removeBooking(bookingId);
            tripRepository.save(trip);
            if (trip.getStatus() == com.ridebatching.trip.TripStatus.CANCELLED) {
                dispatchService.cancelTripAssignments(trip.getId());
            } else {
                routeTiming.afterCancellation(trip);
            }
            
            meterRegistry.counter("bookings.cancellation_replans").increment();
            log.info("Cancellation replan: Removed booking from existing trip");
        } else booking.cancel();

        escalations.resolve(bookingId, routeTiming.now());
        
        Booking saved = bookingRepository.save(booking);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> listBookingsByEmployee(UUID employeeId, Pageable pageable) {
        return bookingRepository.findByEmployeeId(employeeId, pageable)
            .map(this::mapToResponse);
    }



    private BookingResponse mapToResponse(Booking booking) {
        return new BookingResponse(
            booking.getId(),
            booking.getEmployee().getId(),
            new GeoPointDto(booking.getPickupLocation().latitude(), booking.getPickupLocation().longitude()),
            new GeoPointDto(booking.getDropoffLocation().latitude(), booking.getDropoffLocation().longitude()),
            booking.getSlotTimestamp(),
            booking.getStatus(),
            booking.getPickupSlaDeadline(),
            booking.getTripId(),
            booking.getCreatedAt()
        );
    }
}
