package mwd.trading.broker.ibkr.callback;

import mwd.trading.state.Blackboard;

public class NextValidIdHandler {
    private final Blackboard blackboard;
    
    public NextValidIdHandler(Blackboard blackboard) {
    	this.blackboard = blackboard;    	
    }
    
    public void initialize(int orderId) {
    	this.blackboard.initializeOrderId(orderId);
    }


}
