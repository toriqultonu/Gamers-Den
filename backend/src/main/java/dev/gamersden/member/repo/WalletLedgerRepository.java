package dev.gamersden.member.repo;

import dev.gamersden.member.domain.WalletLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntry, Long> {

    List<WalletLedgerEntry> findByMemberIdOrderByIdDesc(Long memberId);
}
