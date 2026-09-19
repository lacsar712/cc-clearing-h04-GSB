package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettingApplicationServiceSettleTest {

    private static final LocalDate SETTLE_DATE = LocalDate.of(2026, 9, 10);

    private Map<String, NettingRun> runs;
    private Map<String, List<TradeObligation>> obligationsByRun;
    private NettingApplicationService service;

    @BeforeEach
    void setUp() {
        runs = new HashMap<>();
        obligationsByRun = new HashMap<>();

        NettingRunRepositoryPort runRepository = new NettingRunRepositoryPort() {
            @Override
            public NettingRun save(NettingRun run) {
                runs.put(run.getRunId(), run);
                return run;
            }

            @Override
            public Optional<NettingRun> findById(String runId) {
                return Optional.ofNullable(runs.get(runId));
            }

            @Override
            public List<NettingRun> findAllOrderByCreatedAtDesc() {
                return List.copyOf(runs.values());
            }
        };
        ObligationRepositoryPort obligationRepository = new ObligationRepositoryPort() {
            @Override
            public TradeObligation save(TradeObligation obligation) {
                return obligation;
            }

            @Override
            public List<TradeObligation> saveAll(List<TradeObligation> obligations) {
                return obligations;
            }

            @Override
            public Optional<TradeObligation> findById(String obligationId) {
                return Optional.empty();
            }

            @Override
            public List<TradeObligation> findAll() {
                return List.of();
            }

            @Override
            public List<TradeObligation> findByFilters(String currency, LocalDate settleDate, ObligationStatus status) {
                return List.of();
            }

            @Override
            public List<TradeObligation> findOpenBySettleDateAndCurrency(LocalDate settleDate, String currency) {
                return List.of();
            }

            @Override
            public List<TradeObligation> findByNettingRunId(String runId) {
                return obligationsByRun.getOrDefault(runId, List.of());
            }
        };
        MemberRepositoryPort memberRepository = new MemberRepositoryPort() {
            @Override
            public Member save(Member member) {
                return member;
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
        };
        NetPositionRepositoryPort positionRepository = new NetPositionRepositoryPort() {
            @Override
            public List<NetPosition> saveAll(List<NetPosition> positions) {
                return positions;
            }

            @Override
            public List<NetPosition> findByRunId(String runId) {
                return List.of();
            }
        };

        service = new NettingApplicationService(
                runRepository,
                obligationRepository,
                memberRepository,
                positionRepository,
                new NettingRunStatusService(runRepository));
    }

    @Test
    void completedRunSettlesNettedObligations() {
        putRun("run-completed", NettingRunStatus.COMPLETED);
        List<TradeObligation> obligations = new ArrayList<>(List.of(
                obligation("o1", ObligationStatus.NETTED),
                obligation("o2", ObligationStatus.NETTED)));
        obligationsByRun.put("run-completed", obligations);

        NettingRun result = service.settle("run-completed");

        assertEquals(NettingRunStatus.COMPLETED, result.getStatus());
        assertEquals(List.of(ObligationStatus.SETTLED, ObligationStatus.SETTLED),
                obligations.stream().map(TradeObligation::getStatus).toList());
    }

    @Test
    void failedRunRejectsSettleWith4xxDomainError() {
        putRun("run-failed", NettingRunStatus.FAILED);
        List<TradeObligation> obligations = new ArrayList<>(List.of(obligation("o1", ObligationStatus.NETTED)));
        obligationsByRun.put("run-failed", obligations);

        DomainException ex = assertThrows(DomainException.class, () -> service.settle("run-failed"));
        assertEquals("INVALID_STATE", ex.getCode());
        // Obligations must be left untouched on rejection.
        assertEquals(ObligationStatus.NETTED, obligations.get(0).getStatus());
    }

    @Test
    void runningRunRejectsSettleWith4xxDomainError() {
        putRun("run-running", NettingRunStatus.RUNNING);
        obligationsByRun.put("run-running",
                new ArrayList<>(List.of(obligation("o1", ObligationStatus.NETTED))));

        DomainException ex = assertThrows(DomainException.class, () -> service.settle("run-running"));
        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    void createdRunRejectsSettle() {
        putRun("run-created", NettingRunStatus.CREATED);
        obligationsByRun.put("run-created",
                new ArrayList<>(List.of(obligation("o1", ObligationStatus.NETTED))));

        DomainException ex = assertThrows(DomainException.class, () -> service.settle("run-created"));
        assertEquals("INVALID_STATE", ex.getCode());
    }

    @Test
    void settlingAlreadySettledRunIsIdempotent() {
        putRun("run-settled", NettingRunStatus.COMPLETED);
        List<TradeObligation> obligations = new ArrayList<>(List.of(
                obligation("o1", ObligationStatus.SETTLED),
                obligation("o2", ObligationStatus.SETTLED)));
        obligationsByRun.put("run-settled", obligations);

        NettingRun result = service.settle("run-settled");

        assertEquals(NettingRunStatus.COMPLETED, result.getStatus());
        assertTrue(obligations.stream().allMatch(o -> o.getStatus() == ObligationStatus.SETTLED));
    }

    @Test
    void openObligationsAreNeverForcePromotedToSettled() {
        putRun("run-mixed", NettingRunStatus.COMPLETED);
        List<TradeObligation> obligations = new ArrayList<>(List.of(
                obligation("o1", ObligationStatus.NETTED),
                obligation("o2", ObligationStatus.OPEN)));
        obligationsByRun.put("run-mixed", obligations);

        service.settle("run-mixed");

        assertEquals(ObligationStatus.SETTLED, obligations.get(0).getStatus());
        assertEquals(ObligationStatus.OPEN, obligations.get(1).getStatus());
    }

    private void putRun(String runId, NettingRunStatus status) {
        runs.put(runId, new NettingRun(runId, SETTLE_DATE, "USD", status, Instant.now(), null));
    }

    private TradeObligation obligation(String obligationId, ObligationStatus status) {
        return new TradeObligation(
                obligationId,
                "A",
                "B",
                "USD",
                new BigDecimal("10"),
                SETTLE_DATE.minusDays(1),
                SETTLE_DATE,
                status,
                null);
    }
}
