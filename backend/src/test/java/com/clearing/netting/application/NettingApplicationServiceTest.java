package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettingApplicationServiceTest {

    private static final LocalDate SETTLE_DATE = LocalDate.of(2026, 9, 10);

    private InMemoryRunRepository runRepository;
    private InMemoryObligationRepository obligationRepository;
    private NettingApplicationService service;

    @BeforeEach
    void setUp() {
        runRepository = new InMemoryRunRepository();
        obligationRepository = new InMemoryObligationRepository();
        service = new NettingApplicationService(
                runRepository,
                obligationRepository,
                new UnusedMemberRepository(),
                new UnusedPositionRepository(),
                new NettingRunStatusService(runRepository));
    }

    @Test
    void settleCompletedRunSettlesNettedObligations() {
        NettingRun run = completedRun();
        TradeObligation o1 = nettedObligation(run, "A", "B", "100");
        TradeObligation o2 = nettedObligation(run, "B", "C", "60");

        NettingRun result = service.settle(run.getRunId());

        assertEquals(run.getRunId(), result.getRunId());
        assertEquals(ObligationStatus.SETTLED, o1.getStatus());
        assertEquals(ObligationStatus.SETTLED, o2.getStatus());
        assertEquals(ObligationStatus.SETTLED,
                obligationRepository.findById(o1.getObligationId()).orElseThrow().getStatus());
    }

    @Test
    void settleRejectsFailedRun() {
        NettingRun run = runWithStatus(RunState.FAILED);
        TradeObligation o = nettedObligation(run, "A", "B", "100");

        DomainException ex = assertThrows(DomainException.class, () -> service.settle(run.getRunId()));

        assertEquals("INVALID_STATE", ex.getCode());
        assertEquals(ObligationStatus.NETTED, o.getStatus());
    }

    @Test
    void settleRejectsRunningRun() {
        NettingRun run = runWithStatus(RunState.RUNNING);
        nettedObligation(run, "A", "B", "100");

        DomainException ex = assertThrows(DomainException.class, () -> service.settle(run.getRunId()));

        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    void settleRejectsCreatedRun() {
        NettingRun run = runWithStatus(RunState.CREATED);
        nettedObligation(run, "A", "B", "100");

        DomainException ex = assertThrows(DomainException.class, () -> service.settle(run.getRunId()));

        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    void settleAlreadySettledRunIsIdempotent() {
        NettingRun run = completedRun();
        TradeObligation o = nettedObligation(run, "A", "B", "100");
        o.markSettled();
        obligationRepository.save(o);

        NettingRun result = service.settle(run.getRunId());

        assertEquals(run.getRunId(), result.getRunId());
        assertEquals(ObligationStatus.SETTLED, o.getStatus());
    }

    @Test
    void settleNeverForcePromotesOpenObligations() {
        NettingRun run = completedRun();
        TradeObligation open = new TradeObligation(
                "ob-open-1", "A", "B", "USD", new BigDecimal("10"),
                SETTLE_DATE.minusDays(1), SETTLE_DATE, ObligationStatus.OPEN, run.getRunId());
        obligationRepository.save(open);

        DomainException ex = assertThrows(DomainException.class, () -> service.settle(run.getRunId()));

        assertEquals("INVALID_STATE", ex.getCode());
        assertEquals(ObligationStatus.OPEN, open.getStatus());
        assertEquals(ObligationStatus.OPEN,
                obligationRepository.findById(open.getObligationId()).orElseThrow().getStatus());
    }

    private NettingRun completedRun() {
        return runWithStatus(RunState.COMPLETED);
    }

    private NettingRun runWithStatus(RunState state) {
        NettingRun run = NettingRun.create(SETTLE_DATE, "USD");
        switch (state) {
            case RUNNING, COMPLETED, FAILED -> run.markRunning();
            default -> {
            }
        }
        switch (state) {
            case COMPLETED -> run.markCompleted();
            case FAILED -> run.markFailed("netting blew up");
            default -> {
            }
        }
        return runRepository.save(run);
    }

    private TradeObligation nettedObligation(NettingRun run, String payer, String payee, String amount) {
        TradeObligation o = TradeObligation.open(
                payer, payee, "USD", new BigDecimal(amount),
                SETTLE_DATE.minusDays(1), SETTLE_DATE);
        o.markNetted(run.getRunId());
        return obligationRepository.save(o);
    }

    private enum RunState {
        CREATED, RUNNING, COMPLETED, FAILED
    }

    static class InMemoryRunRepository implements NettingRunRepositoryPort {
        private final Map<String, NettingRun> store = new LinkedHashMap<>();

        @Override
        public NettingRun save(NettingRun run) {
            store.put(run.getRunId(), run);
            return run;
        }

        @Override
        public Optional<NettingRun> findById(String runId) {
            return Optional.ofNullable(store.get(runId));
        }

        @Override
        public List<NettingRun> findAllOrderByCreatedAtDesc() {
            return new ArrayList<>(store.values());
        }
    }

    static class InMemoryObligationRepository implements ObligationRepositoryPort {
        private final Map<String, TradeObligation> store = new LinkedHashMap<>();

        @Override
        public TradeObligation save(TradeObligation obligation) {
            store.put(obligation.getObligationId(), obligation);
            return obligation;
        }

        @Override
        public List<TradeObligation> saveAll(List<TradeObligation> obligations) {
            obligations.forEach(o -> store.put(o.getObligationId(), o));
            return obligations;
        }

        @Override
        public Optional<TradeObligation> findById(String obligationId) {
            return Optional.ofNullable(store.get(obligationId));
        }

        @Override
        public List<TradeObligation> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<TradeObligation> findByFilters(String currency, LocalDate settleDate, ObligationStatus status) {
            throw new UnsupportedOperationException("not needed for settle tests");
        }

        @Override
        public List<TradeObligation> findOpenBySettleDateAndCurrency(LocalDate settleDate, String currency) {
            throw new UnsupportedOperationException("not needed for settle tests");
        }

        @Override
        public List<TradeObligation> findByNettingRunId(String runId) {
            return store.values().stream()
                    .filter(o -> runId.equals(o.getNettingRunId()))
                    .collect(Collectors.toList());
        }
    }

    static class UnusedMemberRepository implements MemberRepositoryPort {
        @Override
        public Member save(Member member) {
            throw new UnsupportedOperationException("not needed for settle tests");
        }

        @Override
        public Optional<Member> findById(String memberId) {
            return Optional.empty();
        }

        @Override
        public List<Member> findAll() {
            return List.of();
        }

        @Override
        public List<Member> findByIds(Iterable<String> memberIds) {
            return List.of();
        }
    }

    static class UnusedPositionRepository implements NetPositionRepositoryPort {
        @Override
        public List<NetPosition> saveAll(List<NetPosition> positions) {
            throw new UnsupportedOperationException("not needed for settle tests");
        }

        @Override
        public List<NetPosition> findByRunId(String runId) {
            return List.of();
        }
    }
}
