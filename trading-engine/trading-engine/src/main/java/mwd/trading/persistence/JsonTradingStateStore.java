package mwd.trading.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ib.client.Decimal;

import mwd.trading.execution.BracketOrder;

/**
 * Small, atomically-written JSON journal for trading-engine order intent and broker
 * acknowledgements. IBKR remains the authority; this file records what this
 * process intended and last observed.
 */
public final class JsonTradingStateStore {
    public static final int SCHEMA_VERSION = 1;

    public record LegRecord(
            String role,
            int apiOrderId,
            long permanentId,
            String orderReference,
            String status,
            String filledQuantity,
            String remainingQuantity,
            boolean acknowledged) {
    }

    public record TradeRecord(
            String tradeId,
            String strategy,
            String account,
            int contractId,
            String symbol,
            String action,
            String totalQuantity,
            double entryPrice,
            String status,
            long createdAtEpochMillis,
            long updatedAtEpochMillis,
            List<LegRecord> legs) {
        public TradeRecord {
            legs = List.copyOf(legs);
        }

        public boolean terminal() {
            return "FILLED".equals(status) || "CANCELLED".equals(status) || "REJECTED".equals(status);
        }
    }

    public record StateFile(
            int schemaVersion,
            long writtenAtEpochMillis,
            List<TradeRecord> trades) {
        public StateFile {
            trades = List.copyOf(trades);
        }
    }

    private final Path statePath;
    private final Path temporaryPath;
    private final Path backupPath;
    private final ObjectMapper objectMapper;
    private final Map<String, TradeRecord> tradesById = new LinkedHashMap<>();
    private boolean recoveredFromBackup;

    public JsonTradingStateStore(Path statePath) throws IOException {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.temporaryPath = this.statePath.resolveSibling(this.statePath.getFileName() + ".tmp");
        this.backupPath = this.statePath.resolveSibling(backupFileName(this.statePath));
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    public synchronized boolean recoveredFromBackup() {
        return recoveredFromBackup;
    }

    public synchronized StateFile snapshot() {
        List<TradeRecord> trades = new ArrayList<>(tradesById.values());
        trades.sort(Comparator.comparing(TradeRecord::createdAtEpochMillis).thenComparing(TradeRecord::tradeId));
        return new StateFile(SCHEMA_VERSION, System.currentTimeMillis(), trades);
    }

    public synchronized List<TradeRecord> activeTrades() {
        return tradesById.values().stream().filter(trade -> !trade.terminal()).toList();
    }

    public synchronized Optional<TradeRecord> findTrade(String tradeId) {
        return Optional.ofNullable(tradesById.get(tradeId));
    }

    public synchronized void recordIntent(BracketOrder bracketOrder, String action) throws IOException {
        List<LegRecord> legs = bracketOrder.getLegStates().stream()
                .map(JsonTradingStateStore::toLegRecord)
                .toList();
        long now = System.currentTimeMillis();
        TradeRecord existing = tradesById.get(bracketOrder.getTradeId());
        long createdAt = existing == null ? now : existing.createdAtEpochMillis();

        tradesById.put(bracketOrder.getTradeId(), new TradeRecord(
                bracketOrder.getTradeId(),
                bracketOrder.getStrategyName(),
                bracketOrder.getAccount(),
                bracketOrder.getContractId(),
                bracketOrder.getTicker(),
                action,
                decimalText(bracketOrder.getTotalQuantity()),
                bracketOrder.getEntryPrice(),
                bracketOrder.getStatus().name(),
                createdAt,
                now,
                legs));
        persist();
    }

    public synchronized void recordBrokerUpdate(BracketOrder bracketOrder, String action) throws IOException {
        recordIntent(bracketOrder, action);
    }

    private void load() throws IOException {
        if (!Files.exists(statePath)) {
            return;
        }

        try {
            loadFrom(statePath);
        } catch (IOException primaryFailure) {
            if (!Files.exists(backupPath)) {
                throw primaryFailure;
            }
            loadFrom(backupPath);
            recoveredFromBackup = true;
        }
    }

    private void loadFrom(Path source) throws IOException {
        StateFile state = objectMapper.readValue(source.toFile(), StateFile.class);
        if (state.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException("Unsupported trading-state schema version: " + state.schemaVersion());
        }
        tradesById.clear();
        for (TradeRecord trade : state.trades()) {
            tradesById.put(trade.tradeId(), trade);
        }
    }

    private void persist() throws IOException {
        Path parent = statePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        StateFile state = snapshot();
        byte[] serializedState = objectMapper.writeValueAsBytes(state);
        try (FileChannel channel = FileChannel.open(
                temporaryPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(serializedState);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }

        if (Files.exists(statePath)) {
            Files.copy(statePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(temporaryPath, statePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryPath, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static LegRecord toLegRecord(BracketOrder.OrderLegState leg) {
        return new LegRecord(
                leg.getRole(),
                leg.getApiOrderId(),
                leg.getPermanentId(),
                leg.getOrderReference(),
                leg.getStatus(),
                decimalText(leg.getFilledQuantity()),
                decimalText(leg.getRemainingQuantity()),
                leg.isAcknowledged());
    }

    private static String decimalText(Decimal value) {
        return value == null ? "0" : value.toString();
    }

    private static String backupFileName(Path path) {
        String fileName = path.getFileName().toString();
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return fileName + ".backup";
        }
        return fileName.substring(0, extensionIndex) + ".backup" + fileName.substring(extensionIndex);
    }
}
