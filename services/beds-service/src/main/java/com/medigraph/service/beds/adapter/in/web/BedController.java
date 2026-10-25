package com.medigraph.service.beds.adapter.in.web;

import com.medigraph.beds.BedHold;
import com.medigraph.beds.BedInventory;
import com.medigraph.beds.geo.GeoPoint;
import com.medigraph.common.id.HealthId;
import com.medigraph.service.beds.application.BedAllocation;
import com.medigraph.service.beds.application.BedAllocation.HospitalView;
import com.medigraph.service.beds.domain.Capacity;
import com.medigraph.service.beds.domain.HospitalId;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1")
public class BedController {

    public record HospitalRequest(@NotBlank @Size(max = 200) String name,
                                  @DecimalMin("-90") @DecimalMax("90") double lat,
                                  @DecimalMin("-180") @DecimalMax("180") double lon,
                                  @Min(0) int icuBeds, @Min(0) int occupied) {
    }

    public record HoldRequest(@NotBlank String hospitalId, @NotBlank String patientId,
                              @Min(1) @Max(120) Integer ttlMinutes) {
    }

    public record InventoryView(String hospitalId, int total, int occupied, int held, int free, long version,
                                String shard) {
    }

    private final BedAllocation beds;

    public BedController(BedAllocation beds) {
        this.beds = beds;
    }

    @Operation(summary = "Register a hospital or update its ICU capacity (CAS-safe against concurrent holds)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/hospitals/{hospitalId}")
    public Map<String, String> upsert(@PathVariable String hospitalId, @Valid @RequestBody HospitalRequest r) {
        HospitalId id = new HospitalId(hospitalId);
        String shard = beds.upsertHospital(id, r.name(), new GeoPoint(r.lat(), r.lon()),
                new Capacity(r.icuBeds(), r.occupied()));
        return Map.of("hospitalId", id.value(), "shard", shard);
    }

    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','RECEPTION','ADMIN')")
    @GetMapping("/hospitals/{hospitalId}/beds")
    public InventoryView inventory(@PathVariable String hospitalId) {
        HospitalId id = new HospitalId(hospitalId);
        BedInventory i = beds.inventory(id);
        return new InventoryView(i.hospitalId(), i.total(), i.occupied(), i.held(), i.free(), i.version(),
                beds.shardFor(id));
    }

    @Operation(summary = "Nearest K hospitals with a free ICU bed")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','RECEPTION','ADMIN')")
    @GetMapping("/beds/nearest")
    public List<HospitalView> nearest(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                      @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
                                      @RequestParam(defaultValue = "5") @Min(1) @Max(50) int k,
                                      @RequestParam(defaultValue = "50") @DecimalMin("0.1") @DecimalMax("2000") double maxKm) {
        return beds.nearest(new GeoPoint(lat, lon), k, maxKm);
    }

    @Operation(summary = "Hold a bed for an incoming patient; expires after the TTL unless confirmed")
    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN')")
    @PostMapping("/beds/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public BedHold hold(@Valid @RequestBody HoldRequest r) {
        return beds.hold(new HospitalId(r.hospitalId()), HealthId.parse(r.patientId()),
                r.ttlMinutes() == null ? null : Duration.ofMinutes(r.ttlMinutes()));
    }

    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN')")
    @PostMapping("/beds/holds/{holdId}/confirm")
    public BedHold confirm(@PathVariable String holdId) {
        return beds.confirm(holdId);
    }

    @PreAuthorize("hasAnyRole('DOCTOR','NURSE','ADMIN')")
    @DeleteMapping("/beds/holds/{holdId}")
    public Map<String, Boolean> release(@PathVariable String holdId) {
        return Map.of("released", beds.release(holdId));
    }
}
