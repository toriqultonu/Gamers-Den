package dev.gamersden.printing.repo;

import dev.gamersden.printing.domain.PrintJob;
import dev.gamersden.printing.domain.PrintJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {

    List<PrintJob> findByStatusInOrderByIdAsc(Collection<PrintJobStatus> statuses);
}
