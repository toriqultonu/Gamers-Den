package dev.gamersden.member.repo;

import dev.gamersden.member.domain.PointsLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointsLedgerRepository extends JpaRepository<PointsLedgerEntry, Long> {

    List<PointsLedgerEntry> findByMemberIdOrderByIdDesc(Long memberId);
}
