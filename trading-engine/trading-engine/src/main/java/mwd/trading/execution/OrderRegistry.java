package mwd.trading.execution;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class OrderRegistry {
    private final Map<Integer, BracketOrder> identifierToBracketMap = new ConcurrentHashMap<>();
    private final Map<Long, BracketOrder> permanentIdentifierToBracketMap = new ConcurrentHashMap<>();
    private final Map<String, BracketOrder> orderReferenceToBracketMap = new ConcurrentHashMap<>();
    private final Map<String, BracketOrder> tradeIdentifierToBracketMap = new ConcurrentHashMap<>();

    /**
     * Registers all local and durable identifiers for a bracket.
     */
    public void register(BracketOrder bracketOrder) {
        tradeIdentifierToBracketMap.put(bracketOrder.getTradeId(), bracketOrder);
        identifierToBracketMap.put(bracketOrder.getParentOrderId(), bracketOrder);

        for (BracketOrder.ExitSlice exitSlice : bracketOrder.getSlices()) {
            identifierToBracketMap.put(exitSlice.getTakeProfitOrderId(), bracketOrder);
            identifierToBracketMap.put(exitSlice.getStopLossOrderId(), bracketOrder);
            identifierToBracketMap.put(exitSlice.getTimeExitOrderId(), bracketOrder);
        }

        for (BracketOrder.OrderLegState legState : bracketOrder.getLegStates()) {
            if (legState.getOrderReference() != null && !legState.getOrderReference().isBlank()) {
                orderReferenceToBracketMap.put(legState.getOrderReference(), bracketOrder);
            }
            if (legState.getPermanentId() > 0) {
                permanentIdentifierToBracketMap.put(legState.getPermanentId(), bracketOrder);
            }
        }
    }

    public BracketOrder getBracketFor(int orderIdentifier) {
        return identifierToBracketMap.get(orderIdentifier);
    }

    public BracketOrder getBracketForPermanentId(long permanentIdentifier) {
        return permanentIdentifierToBracketMap.get(permanentIdentifier);
    }

    public BracketOrder getBracketForOrderReference(String orderReference) {
        return orderReference == null ? null : orderReferenceToBracketMap.get(orderReference);
    }

    public BracketOrder getBracketForTradeId(String tradeIdentifier) {
        return tradeIdentifier == null ? null : tradeIdentifierToBracketMap.get(tradeIdentifier);
    }

    public void recordBrokerIdentity(int orderIdentifier, long permanentIdentifier, String orderReference) {
        BracketOrder bracketOrder = identifierToBracketMap.get(orderIdentifier);
        if (bracketOrder == null && orderReference != null) {
            bracketOrder = orderReferenceToBracketMap.get(orderReference);
        }
        if (bracketOrder == null) {
            return;
        }
        if (permanentIdentifier > 0) {
            permanentIdentifierToBracketMap.put(permanentIdentifier, bracketOrder);
        }
        if (orderReference != null && !orderReference.isBlank()) {
            orderReferenceToBracketMap.put(orderReference, bracketOrder);
        }
    }

    public Set<BracketOrder> getAllBracketsSnapshot() {
        return tradeIdentifierToBracketMap.values().stream().collect(Collectors.toUnmodifiableSet());
    }

    public void unregister(BracketOrder bracketOrder) {
        tradeIdentifierToBracketMap.remove(bracketOrder.getTradeId(), bracketOrder);
        identifierToBracketMap.remove(bracketOrder.getParentOrderId());

        for (BracketOrder.ExitSlice exitSlice : bracketOrder.getSlices()) {
            identifierToBracketMap.remove(exitSlice.getTakeProfitOrderId());
            identifierToBracketMap.remove(exitSlice.getStopLossOrderId());
            identifierToBracketMap.remove(exitSlice.getTimeExitOrderId());
        }

        orderReferenceToBracketMap.entrySet().removeIf(entry -> entry.getValue() == bracketOrder);
        permanentIdentifierToBracketMap.entrySet().removeIf(entry -> entry.getValue() == bracketOrder);
    }
}
