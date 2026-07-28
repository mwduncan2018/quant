package mwd.trading.broker.ibkr.callback;

import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Contract;
import com.ib.client.Decimal;

import mwd.trading.domain.Account;
import mwd.trading.state.Blackboard;
import mwd.trading.reconciliation.ReconciliationManager;

public class AccountEventHandler {
	private static final Logger logger = LogManager.getLogger(AccountEventHandler.class);
	private final Blackboard blackboard;
	private final ReconciliationManager reconciliationManager;

	public AccountEventHandler(Blackboard blackboard, ReconciliationManager reconciliationManager) {
		this.blackboard = Objects.requireNonNull(blackboard, "blackboard");
		this.reconciliationManager = Objects.requireNonNull(
				reconciliationManager, "reconciliationManager");
	}

	public void onManagedAccounts(String accountsList) {
		logger.info(">>> ManagedAccountEventHandler.onManagedAccounts - Accounts Received: {}", accountsList);
		String[] accounts = accountsList.split(",");
		if (accounts.length > 0) {
			String primaryAccount = accounts[0];
			blackboard.getAccount().setAccountId(primaryAccount);
		}
	}

	/**
	 * Records that IBKR finished sending a batch of account values.
	 *
	 * <p>
	 * A strategy uses this to require a snapshot newer than its last submitted
	 * order before sizing the next one, so several entries cannot all be sized
	 * against the same pre-order balance.
	 */
	public void onUpdateAccountTime(String timeStamp) {
		blackboard.getAccount().setLastRefreshedAtMillis(System.currentTimeMillis());
	}

	public void onUpdateAccountValue(String key, String value, String currency, String accountName) {
		// IBKR reports each value once per held currency plus a BASE summary
		// row. Without this filter a non-USD balance would overwrite the figure
		// position sizing reads, in a currency it was never denominated in.
		if (currency != null && !currency.isBlank()
				&& !"USD".equalsIgnoreCase(currency) && !"BASE".equalsIgnoreCase(currency)) {
			return;
		}

		Account account = blackboard.getAccount();

		try {
			double val = Double.parseDouble(value);
			switch (key) {
			case "NetLiquidation" -> account.setNetLiquidation(val);
			case "TotalCashValue" -> account.setTotalCashValue(val);
			case "SettledCash" -> account.setSettledCash(val);
			case "BuyingPower" -> account.setBuyingPower(val);
			case "AvailableFunds" -> account.setAvailableFunds(val);
			case "ExcessMargin" -> account.setExcessMargin(val);
			case "RealizedPnL" -> account.setRealizedPnL(val);
			case "UnrealizedPnL" -> account.setUnrealizedPnL(val);
			case "Cushion" -> account.setCushion(val);
			}
		} catch (NumberFormatException e) {
			// Skip non-numeric metadata tags from IBKR
		}
	}

	public void onPosition(String account, Contract contract, Decimal pos, double avgCost) {
		reconciliationManager.onPosition(account, contract, pos, avgCost);
		blackboard.getStock(contract.symbol()).setPositionSize(pos);
		blackboard.getStock(contract.symbol()).setAverageCost(avgCost);
	}

	public void onPositionEnd() {
		reconciliationManager.onPositionEnd();
	}

	public void onAccountDownloadEnd(String accountName) {
		logger.info("Account download completed for {}", accountName);
	}

	public void onUpdatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue,
			double averageCost, double unrealizedPNL, double realizedPNL, String accountName) {
		var stock = blackboard.getStock(contract.symbol());
		reconciliationManager.onLivePortfolioPosition(accountName, contract, position, averageCost);
		stock.setPositionSize(position);
		stock.setAverageCost(averageCost);
		stock.setMarketValue(marketValue);
		stock.setRealizedPnL(realizedPNL);
		stock.setUnrealizedPnl(unrealizedPNL);
	}
}
