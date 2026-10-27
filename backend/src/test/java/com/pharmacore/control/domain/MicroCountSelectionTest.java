package com.pharmacore.control.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MicroCountSelectionTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    private static RiskFactors plain(UUID id) {
        return new RiskFactors(id, 5_000, false, false, 0, 0.5, 10, 0);
    }

    private static List<RiskFactors> catalog(int size, long seed) {
        Random random = new Random(seed);
        List<RiskFactors> products = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            products.add(new RiskFactors(new UUID(seed, i), random.nextInt(200_000), random.nextInt(10) == 0,
                    random.nextInt(8) == 0, random.nextInt(3), random.nextDouble() * 6,
                    random.nextInt(5) == 0 ? null : random.nextInt(90), random.nextInt(3)));
        }
        return products;
    }

    private static List<UUID> ids(List<MicroCountSelection.Pick> picks) {
        return picks.stream().map(p -> p.factors().productId()).toList();
    }

    @Test
    void sameDayAndPharmacyGiveTheSameListWhateverTheCandidateOrder() {
        var products = catalog(80, 7);
        String seed = MicroCountSelection.seed("secret", TENANT, DAY);
        var first = MicroCountSelection.select(products, 6, seed);
        var shuffled = new ArrayList<>(products);
        Collections.shuffle(shuffled, new Random(99));
        var second = MicroCountSelection.select(shuffled, 6, seed);

        assertThat(first).hasSize(6);
        assertThat(ids(second)).containsExactlyElementsOf(ids(first));
    }

    @Test
    void anotherDayAnotherPharmacyOrAnotherSecretGiveAnotherList() {
        var products = catalog(80, 11);
        var today = ids(MicroCountSelection.select(products, 6, MicroCountSelection.seed("secret", TENANT, DAY)));

        int differentDays = 0;
        for (int d = 1; d <= 10; d++) {
            var other = ids(MicroCountSelection.select(products, 6,
                    MicroCountSelection.seed("secret", TENANT, DAY.plusDays(d))));
            if (!other.equals(today)) {
                differentDays++;
            }
        }
        assertThat(differentDays).isEqualTo(10);
        assertThat(ids(MicroCountSelection.select(products, 6,
                MicroCountSelection.seed("secret", UUID.randomUUID(), DAY)))).isNotEqualTo(today);
        // without the pharmacy's secret, tomorrow's list cannot be computed in advance
        assertThat(ids(MicroCountSelection.select(products, 6,
                MicroCountSelection.seed("guess", TENANT, DAY)))).isNotEqualTo(today);
    }

    @Test
    void riskyProductsAreDrawnFarMoreOftenThanPlainOnes() {
        UUID narcotic = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        List<RiskFactors> products = new ArrayList<>();
        products.add(new RiskFactors(narcotic, 150_000, true, true, 2, 4, null, 2));
        for (int i = 0; i < 49; i++) {
            products.add(plain(new UUID(1, i)));
        }
        Map<UUID, Integer> drawn = new HashMap<>();
        for (int d = 0; d < 400; d++) {
            MicroCountSelection.select(products, 5, MicroCountSelection.seed("s", TENANT, DAY.plusDays(d)))
                    .forEach(p -> drawn.merge(p.factors().productId(), 1, Integer::sum));
        }
        double plainAverage = products.stream().skip(1).mapToInt(p -> drawn.getOrDefault(p.productId(), 0))
                .average().orElse(0);
        assertThat(drawn.get(narcotic)).isGreaterThan(250);
        assertThat(drawn.get(narcotic)).isGreaterThan((int) (plainAverage * 5));
        // a plain product still has its chance
        assertThat(products.stream().skip(1).filter(p -> drawn.containsKey(p.productId())).count()).isGreaterThan(40);
    }

    @Test
    void sizeIsBoundedByTheCandidates() {
        var products = catalog(3, 5);
        assertThat(MicroCountSelection.select(products, 6, "seed")).hasSize(3);
        assertThat(MicroCountSelection.select(List.of(), 6, "seed")).isEmpty();
    }

    @Test
    void uniformValuesStayStrictlyBetweenZeroAndOne() {
        for (int i = 0; i < 1_000; i++) {
            double u = MicroCountSelection.uniform("seed-" + i, UUID.randomUUID());
            assertThat(u).isGreaterThan(0).isLessThan(1);
        }
    }
}
