package mwd.trading.broker.ibkr.callback;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import mwd.trading.state.Blackboard;

public class IbkrErrorHandler {
	private final Blackboard blackboard;
    private static final Logger logger = LogManager.getLogger(IbkrErrorHandler.class);

    public IbkrErrorHandler(Blackboard blackboard) {
		this.blackboard = blackboard;
	}
	
    public void process(int reqId, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        logger.error("TWS Error - ID: {}, Code: {}, Message: {}, Advanced: {}", 
                     reqId, errorCode, errorMsg, advancedOrderRejectJson);

        
        if (reqId > 0) {
        	if (errorCode == 201) {
        		// Find the order in the blackboard that has the reqId
        		// Update this order's status to ORDER REJECTED
        	} else if (errorCode == 202) {
        		// Find the order in the blackboard that has the reqId
        		// Update this order's status to ORDER CANCELED
        	}
        }
        
        if (reqId <= 0) {
        	if (errorCode == 503 || errorCode == 505 || errorCode == 507 || errorCode == 508 || errorCode == 323) {
        		blackboard.setSystemUpdateRequired(true);
        	}
        }
    }
    
}

/*
 * Immediate Automated Reconnection Required:
 * 502 Could not connect to TWS
 * 504 Not connected
 * 509 Exception when reading socket (TWS crashed or OS killed port)
 * 
 * Reconnection Required With Manual Effort:
 * 326 Client ID already in use
 * 507 Version mismatch (
 * 503 Upgrade required
 * 
 * Wait & Listen:
 * 1100 Connectivity Lost (TWS lost connection to IBKR servers)
 * Everything else... Display info to the console
 * 
 * Reconnection Strategy:
 * Attempt reconnection immediately
 * Wait 10 seconds and attempt
 * Wait 30 seconds and attempt
 * Switch client ID, connect, and run reconciliation order sync
 * 
 * 
 */

// Additional Info Below

/*
 * Local Layer - These codes say if Java can talk to the IBKR TWS on this machine
 * 502 Couldn't connect to TWS 
 * 504 Not connected
 * 507 Bad API version
 * 
 * Broker Layer - These monitor the connection between IBKR TWS and the IBKR headquarters
 * 1100 Connectivity Lost
 * 1101 Connectivity Restored
 * 1102 Restored
 * 
 * Data Layer - General connection is fine, but the specific server providing data for this ticker goes down
 * 2103 Price Tick Connection Broken
 * 2104 Price Tick Connection Restored
 * 2105 Historical Bars Connection Broken
 * 2106 Historical Bars Connection Restored
 * 2107 Historical Data Farm Connection Broken (Connection fine, but no data is coming from the data farm)
 * 2108 Historical Data Farm Connection Restored
 * 2157 Security Definition Connection Broken (This means the IBKR system is broken and is not processing)
 * 2158 Security Definition Connection Restored (Security Definition is a sub-system that bridges ticker to a Contract ID)
 * 
 * Handshake Layer - These would occur before nextValidId occurs
 * 326 Unable To Connect Client Id Already In Use
 * 501 Already Connected
 * 503 TWS Must Be Upgraded
 * 509 Exception When Reading Socket (TWS crashed or the OS closed the port)
 * 
 * Other
 * 321 Error Validating Request (you did not finish 2FA)
 * 10167 Connectivity Between IBKR and TWS Lost Read Only Mode
 * 
 * 
 * This is when wireless home internet goes down
 * 		1100
 * 
 * 
 */


