package mwd.trading.broker.ibkr.callback;

import mwd.trading.state.Blackboard;

public class BrokerTimeHandler {
    private final Blackboard blackboard;

    public Blackboard getBlackboard() {
        return blackboard;
    }

    public BrokerTimeHandler(Blackboard blackboard) {
        this.blackboard = blackboard;
    }

    public void onCurrentTime(long time) {
        long localNow = System.currentTimeMillis();
        // Assuming Blackboard has a getTimeManager() method
        blackboard.getTimeManager().setOffset(time, localNow);
        
        System.out.println("Market time synced. Offset: " + 
            blackboard.getTimeManager().getMarketTime() + " ms");
    }
}