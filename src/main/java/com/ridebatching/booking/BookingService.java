package com.ridebatching.booking;

import com.ridebatching.booking.dto.BookingRequest;
import com.ridebatching.booking.dto.BookingResponse;
import com.ridebatching.booking.dto.GeoPointDto;
import com.ridebatching.booking.exception.UnserviceableZoneException;
import com.ridebatching.geo.GeoPoint;
import com.ridebatching.zone.ServiceabilityService;
import com.ridebatching.zone.ServiceableZone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final EmployeeRepository employeeRepository;
    private final ServiceabilityService serviceabilityService;

    public BookingService(BookingRepository bookingRepository,
                          EmployeeRepository employeeRepository,
                          ServiceabilityService serviceabilityService) {
        this.bookingRepository = bookingRepository;
        this.employeeRepository = employeeRepository;
        this.serviceabilityService = serviceabilityService;
    }

    @Transactional
    public BookingResponse createBooking(UUID employeeId, BookingRequest request) {
        GeoPoint pickup = new GeoPoint(request.pickup().latitude(), request.pickup().longitude());
        GeoPoint dropoff = new GeoPoint(request.dropoff().latitude(), request.dropoff().longitude());

        Optional<ServiceableZone> zone = serviceabilityService.findZoneFor(pickup);
        if (zone.isEmpty()) {
            throw new UnserviceableZoneException(
                String.format("Pickup location [%.4f, %.4f] is outside all active serviceable zones.",
                    pickup.latitude(), pickup.longitude())
            );
        }

        Employee employee = employeeRepository.findById(employeeId)
            .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        Instant now = Instant.now();
        Instant slotTimestamp = normalizeTo15MinuteSlot(now);
        Instant slaDeadline = now.plus(20, ChronoUnit.MINUTES);

        Booking booking = new Booking(
            UUID.randomUUID(),
            employee,
            pickup,
            dropoff,
            slotTimestamp,
            BookingStatus.PENDING,
            slaDeadline,
            now
        );

        Booking saved = bookingRepository.save(booking);
        return mapToResponse(saved);
    }

    private Instant normalizeTo15MinuteSlot(Instant time) {
        LocalDateTime ldt = LocalDateTime.ofInstant(time, ZoneOffset.UTC);
        int minute = ldt.getMinute();
        int normalizedMinute = (minute / 15) * 15;
        return ldt.withMinute(normalizedMinute).withSecond(0).withNano(0).toInstant(ZoneOffset.UTC);
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
