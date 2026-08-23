package dev.gamersden.printing.repo;

import dev.gamersden.printing.domain.PrintJob;
import dev.gamersden.printing.domain.PrintJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {

    List<PrintJob> findByStatusInOrderByIdAsc(Collection<PrintJobStatus> statuses);

    /**
     * The next ticket for one device, locked so nobody else can take it
     * (docs/backend-architecture.md §5: "claims with {@code SELECT … FOR UPDATE SKIP LOCKED}").
     *
     * <p>{@code SKIP LOCKED} rather than a plain {@code FOR UPDATE} is the whole point: a second
     * worker — another device's thread, a second app instance during a restart overlap — must walk
     * <em>past</em> a row that is already being claimed instead of queueing behind it. Blocking
     * there would serialise every device onto the slowest one, and a crashed claimant would stall
     * the counter until its lock timed out.
     *
     * <p>{@code ORDER BY id} keeps tickets in the order they were taken, which is the order the
     * customers are standing in.
     */
    @Query(value = """
            SELECT * FROM print_jobs
             WHERE status = 'QUEUED' AND device_id = :deviceId
             ORDER BY id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<PrintJob> claimNextFor(@Param("deviceId") String deviceId);

    /**
     * Which devices have paper waiting. The worker asks this rather than scanning every job,
     * because "single-threaded worker per device" means the unit of work is a device, not a job.
     */
    @Query("SELECT DISTINCT j.deviceId FROM PrintJob j WHERE j.status = :status ORDER BY j.deviceId")
    List<String> deviceIdsWithStatus(@Param("status") PrintJobStatus status);
}
