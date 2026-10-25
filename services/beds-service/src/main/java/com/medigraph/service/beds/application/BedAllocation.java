package com.medigraph.service.beds.application;

import com.medigraph.beds.BedHold;
import com.medigraph.beds.BedInventory;
import com.medigraph.beds.BedRepository;
import com.medigraph.beds.BedService;
import com.medigraph.beds.BedUnavailableException;
import com.medigraph.beds.Hospital;
import com.medigraph.beds.HospitalLocator;
import com.medigraph.beds.NearbyHospital;
import com.medigraph.beds.geo.GeoPoint;
import com.medigraph.beds.shard.ConsistentHashRing;
import com.medigraph.common.error.ConflictException;
import com.medigraph.common.error.NotFoundException;
import com.medigraph.common.id.HealthId;
import com.medigraph.common.port.Outbox;
import com.medigraph.common.port.OutboxMessage;
import com.medigraph.common.port.UnitOfWork;
import com.medigraph.service.beds.application.port.out.HospitalDirectory;
import com.medigraph.service.beds.domain.Capacity;
import com.medigraph.service.beds.domain.HospitalId;

import java.time.Duration;
import java.util.List;

/**
 * Use cases of the bed finder. Counters change only through compare-and-set on {@code bed_inventory.version}
 * (see {@link BedService}), so concurrent holds can never oversell, also across service instances.
 * Hospitals are assigned to inventory shards by a consistent-hash ring; in this build every shard is the same
 * database (routing is real, the physical split is not).
 */
public final class BedAllocation {

    public static final Duration MAX_HOLD = Duration.ofHours(2);
    private static final int CAS_ATTEMPTS = 50;

    public record HospitalView(String hospitalId, String name, double distanceKm, int freeIcuBeds, String shard) {
    }

    private final BedService beds;
    private final BedRepository inventory;
    private final HospitalDirectory hospitals;
    private final HospitalLocator locator;
    private final ConsistentHashRing<String> ring;
    private final Outbox outbox;
    private final UnitOfWork tx;
    private final Duration defaultTtl;

    public BedAllocation(BedService beds, BedRepository inventory, HospitalDirectory hospitals, HospitalLocator locator,
                         ConsistentHashRing<String> ring, Outbox outbox, UnitOfWork tx, Duration defaultTtl) {
        this.beds = beds;
        this.inventory = inventory;
        this.hospitals = hospitals;
        this.locator = locator;
        this.ring = ring;
        this.outbox = outbox;
        this.tx = tx;
        this.defaultTtl = defaultTtl;
    }

    /** Registers a hospital or changes its capacity; a capacity change races safely with concurrent holds. */
    public String upsertHospital(HospitalId id, String name, GeoPoint location, Capacity capacity) {
        hospitals.upsert(new Hospital(id.value(), name, location));
        for (int i = 0; i < CAS_ATTEMPTS; i++) {
            BedInventory cur = inventory.find(id.value()).orElse(null);
            if (cur == null) {
                inventory.upsert(new BedInventory(id.value(), capacity.icuBeds(), capacity.occupied(), 0, 0));
                return ring.nodeFor(id.value());
            }
            if (capacity.occupied() + cur.held() > capacity.icuBeds()) {
                throw new ConflictException("capacity " + capacity.icuBeds() + " is below occupied+held ("
                        + capacity.occupied() + "+" + cur.held() + ")");
            }
            BedInventory next = new BedInventory(id.value(), capacity.icuBeds(), capacity.occupied(), cur.held(),
                    cur.version() + 1);
            if (inventory.compareAndSet(cur, next)) {
                return ring.nodeFor(id.value());
            }
        }
        throw new ConflictException("inventory of " + id + " is changing too fast; retry");
    }

    public List<HospitalView> nearest(GeoPoint from, int k, double maxKm) {
        return locator.nearestWithFreeIcu(from, k, maxKm).stream().map(this::view).toList();
    }

    public BedHold hold(HospitalId hospitalId, HealthId patient, Duration ttl) {
        Duration t = ttl == null ? defaultTtl : ttl;
        if (t.isNegative() || t.isZero() || t.compareTo(MAX_HOLD) > 0) {
            throw new IllegalArgumentException("hold ttl must be in (0, " + MAX_HOLD.toMinutes() + "m]");
        }
        inventory(hospitalId);
        try {
            BedHold h = beds.hold(hospitalId.value(), patient.digits(), t);
            event(h, "BedHeld");
            return h;
        } catch (BedUnavailableException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    public BedHold confirm(String holdId) {
        try {
            BedHold h = beds.confirm(holdId);
            event(h, "BedAdmitted");
            return h;
        } catch (BedUnavailableException e) {
            throw new ConflictException(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("hold " + holdId);
        }
    }

    public boolean release(String holdId) {
        try {
            return beds.release(holdId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("hold " + holdId);
        }
    }

    public int expireHolds() {
        return beds.expireHolds();
    }

    public BedInventory inventory(HospitalId hospitalId) {
        return inventory.find(hospitalId.value()).orElseThrow(() -> new NotFoundException("hospital " + hospitalId));
    }

    public String shardFor(HospitalId hospitalId) {
        return ring.nodeFor(hospitalId.value());
    }

    private void event(BedHold h, String type) {
        // the outbox row is its own short transaction: hold state may live in Redis, which cannot join it
        tx.inTransaction(() -> {
            outbox.enqueue(new OutboxMessage(OutboxMessage.EVENTS, "BedHold", h.holdId(), type,
                "{\"holdId\":\"" + h.holdId() + "\",\"hospitalId\":\"" + h.hospitalId() + "\",\"status\":\""
                        + h.status() + "\"}"));
            return null;
        });
    }

    private HospitalView view(NearbyHospital n) {
        return new HospitalView(n.hospital().id(), n.hospital().name(), Math.round(n.distanceKm() * 100) / 100.0,
                n.freeIcuBeds(), ring.nodeFor(n.hospital().id()));
    }
}
