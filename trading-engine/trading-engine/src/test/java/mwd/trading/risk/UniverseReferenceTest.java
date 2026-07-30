package mwd.trading.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UniverseReferenceTest {
    private static final double DEFAULT_LONG = 0.50;
    private static final double DEFAULT_SHORT = 0.55;
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 30);

    private static final List<String> ROWS = List.of(
            "# retrieved: 2026-07-01",
            "ticker,sector,regt_long,regt_short,pm_long,pm_short",
            "TSLA,CONSUMER_DISCRETIONARY,0.50,0.60,0.33,0.40",
            "AAPL,Information Technology,0.50,0.50,0.25,0.30",
            "MU,INFORMATION_TECHNOLOGY,,,,");

    private static UniverseReference parse(MarginMethodology methodology) {
        return UniverseReference.parse(ROWS, methodology, DEFAULT_LONG, DEFAULT_SHORT);
    }

    @Test
    void theSelectedMethodologyPicksWhichPairOfRatesIsUsed() {
        assertEquals(0.50, parse(MarginMethodology.REG_T).marginRate("TSLA", true), 1.0e-9);
        assertEquals(0.60, parse(MarginMethodology.REG_T).marginRate("TSLA", false), 1.0e-9);
        assertEquals(0.33, parse(MarginMethodology.PORTFOLIO).marginRate("TSLA", true), 1.0e-9);
        assertEquals(0.40, parse(MarginMethodology.PORTFOLIO).marginRate("TSLA", false), 1.0e-9);
    }

    @Test
    void aBlankRateFallsBackToTheConservativeDefault() {
        UniverseReference reference = parse(MarginMethodology.REG_T);

        // MU has a row and a sector but no rates collected yet.
        assertEquals(DEFAULT_LONG, reference.marginRate("MU", true), 1.0e-9);
        assertEquals(DEFAULT_SHORT, reference.marginRate("MU", false), 1.0e-9);
        assertEquals(Set.of("MU"), reference.usingDefaultRate(Set.of("TSLA", "AAPL", "MU")));
    }

    @Test
    void aSymbolWithNoRowAtAllGetsTheDefaultAndIsReportedMissing() {
        UniverseReference reference = parse(MarginMethodology.REG_T);

        assertEquals(DEFAULT_LONG, reference.marginRate("NVDA", true), 1.0e-9);
        assertTrue(reference.sector("NVDA").isEmpty());
        assertEquals(Set.of("NVDA"), reference.missingFrom(Set.of("TSLA", "NVDA")));
    }

    @Test
    void sectorsAreParsedRegardlessOfSpacingAndCase() {
        UniverseReference reference = parse(MarginMethodology.REG_T);

        assertEquals(Sector.INFORMATION_TECHNOLOGY, reference.sector("AAPL").orElseThrow());
        assertEquals(Sector.CONSUMER_DISCRETIONARY, reference.sector("tsla").orElseThrow());
    }

    @Test
    void marginRequirementMultipliesNotionalByTheTickersRate() {
        UniverseReference reference = parse(MarginMethodology.PORTFOLIO);

        assertEquals(33_000.0, reference.marginRequirement("TSLA", true, 100_000.0), 1.0e-9);
        // A short position's notional arrives negative; the requirement is not.
        assertEquals(40_000.0, reference.marginRequirement("TSLA", false, -100_000.0), 1.0e-9);
    }

    @Test
    void theRetrievedDateIsReadFromTheCommentAndAged() {
        UniverseReference reference = parse(MarginMethodology.REG_T);

        assertEquals(LocalDate.of(2026, 7, 1), reference.retrieved().orElseThrow());
        assertEquals(29L, reference.ageInDays(TODAY).orElseThrow());
    }

    @Test
    void aTypoInASectorFailsTheFileRatherThanDroppingTheSymbol() {
        // Silently skipping the row would leave the symbol out of its sector
        // total, which is a limit that quietly stops limiting.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> UniverseReference.parse(
                        List.of("TSLA,CONSUMER_DISCRETIONARYY,0.50,0.60,0.33,0.40"),
                        MarginMethodology.REG_T, DEFAULT_LONG, DEFAULT_SHORT));
        assertTrue(thrown.getMessage().contains("TSLA"));
    }

    @Test
    void anImpossibleRateIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> UniverseReference.parse(
                List.of("TSLA,ENERGY,1.5,0.60,0.33,0.40"),
                MarginMethodology.REG_T, DEFAULT_LONG, DEFAULT_SHORT));
        assertThrows(IllegalArgumentException.class, () -> UniverseReference.parse(
                List.of("TSLA,ENERGY,0,0.60,0.33,0.40"),
                MarginMethodology.REG_T, DEFAULT_LONG, DEFAULT_SHORT));
        assertThrows(IllegalArgumentException.class, () -> UniverseReference.parse(
                List.of("TSLA,ENERGY,half,0.60,0.33,0.40"),
                MarginMethodology.REG_T, DEFAULT_LONG, DEFAULT_SHORT));
    }

    @Test
    void aDuplicatedTickerIsRefusedRatherThanSilentlyWinning() {
        assertThrows(IllegalArgumentException.class, () -> UniverseReference.parse(
                List.of("TSLA,ENERGY,0.50,0.60,0.33,0.40",
                        "TSLA,ENERGY,0.90,0.90,0.90,0.90"),
                MarginMethodology.REG_T, DEFAULT_LONG, DEFAULT_SHORT));
    }

    @Test
    void aShortRowIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> UniverseReference.parse(
                List.of("TSLA,ENERGY,0.50"),
                MarginMethodology.REG_T, DEFAULT_LONG, DEFAULT_SHORT));
    }

    @Test
    void aMissingFileLeavesEveryTickerOnDefaults(@TempDir Path directory) throws IOException {
        UniverseReference reference = UniverseReference.load(
                directory.resolve("absent.csv"),
                MarginMethodology.REG_T, DEFAULT_LONG, DEFAULT_SHORT);

        assertEquals(0, reference.coveredTickerCount());
        assertEquals(DEFAULT_LONG, reference.marginRate("TSLA", true), 1.0e-9);
        assertTrue(reference.retrieved().isEmpty());
    }

    @Test
    void loadReadsTheFileFromDisk(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("universe-reference.csv");
        Files.write(file, ROWS, StandardCharsets.UTF_8);

        UniverseReference reference = UniverseReference.load(
                file, MarginMethodology.PORTFOLIO, DEFAULT_LONG, DEFAULT_SHORT);

        assertEquals(3, reference.coveredTickerCount());
        assertEquals(0.33, reference.marginRate("TSLA", true), 1.0e-9);
    }

    @Test
    void theCoverageReportNamesWhatIsMissingAndWhatIsDefaulted() {
        List<String> report = parse(MarginMethodology.REG_T)
                .describeCoverage(Set.of("TSLA", "AAPL", "MU", "NVDA"), TODAY);

        String joined = String.join(" | ", report);
        assertTrue(joined.contains("REG_T"));
        assertTrue(joined.contains("29 days ago"));
        assertTrue(joined.contains("NVDA"), "a symbol with no row must be named");
        assertTrue(joined.contains("MU"), "a symbol on the default rate must be named");
        assertFalse(joined.contains("AAPL"), "a fully covered symbol needs no mention");
    }

    @Test
    void theShippedReferenceFileParsesAndCoversTheConfiguredUniverse() throws IOException {
        Path file = Path.of("data/universe-reference.csv");
        UniverseReference reference = UniverseReference.load(
                file, MarginMethodology.REG_T, DEFAULT_LONG, DEFAULT_SHORT);

        // Rates are deliberately blank until collected from IBKR, but every
        // traded symbol must already carry a sector or its exposure cannot be
        // rolled into a sector total.
        assertEquals(30, reference.coveredTickerCount());
        assertTrue(reference.sector("TSLA").isPresent());
        assertEquals(Sector.FINANCIALS, reference.sector("V").orElseThrow());
        assertEquals(Sector.INDUSTRIALS, reference.sector("UBER").orElseThrow());
    }
}
