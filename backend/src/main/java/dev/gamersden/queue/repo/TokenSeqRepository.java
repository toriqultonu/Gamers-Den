package dev.gamersden.queue.repo;

import dev.gamersden.queue.domain.TokenSeq;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface TokenSeqRepository extends JpaRepository<TokenSeq, LocalDate> {
}
