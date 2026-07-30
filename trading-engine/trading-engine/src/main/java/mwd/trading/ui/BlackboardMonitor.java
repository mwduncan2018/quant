package mwd.trading.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;

import mwd.trading.domain.Account;
import mwd.trading.domain.Stock;
import mwd.trading.execution.BracketOrder;
import mwd.trading.risk.UniverseReference;
import mwd.trading.state.Blackboard;

public class BlackboardMonitor extends JFrame {
	private final int refreshRateMilliseconds = 250;
	private static final long serialVersionUID = 1L;
	private final Blackboard blackboard;
	private final UniverseReference universeReference;
	private final DefaultTableModel tableModel;
	private final JTable monitorTable;
	private final JPanel headerPanel;
	private final JLabel haltStatusLabel, updateRequiredLabel, marketTimeLabel;

	private final JButton view1Button;
	private final JButton view2Button;
	private final JButton viewAllButton;

	private final JLabel netLiquidationValueLabel = new JLabel();
	private final JLabel totalCashValueLabel = new JLabel();
	private final JLabel settledCashValueLabel = new JLabel();
	private final JLabel buyingPowerValueLabel = new JLabel();
	private final JLabel availableFundsValueLabel = new JLabel();
	private final JLabel realizedPnLValueLabel = new JLabel();
	private final JLabel unrealizedPnLValueLabel = new JLabel();
	private final JLabel excessMarginValueLabel = new JLabel();
	private final JLabel marginCushionValueLabel = new JLabel();

	private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
	private final DateTimeFormatter earningsFormatter = DateTimeFormatter.ofPattern("MM/dd");
	private final NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);
	private final NumberFormat percentFormatter = NumberFormat.getPercentInstance(Locale.US);

	private final Color defaultBackgroundGray = new Color(240, 240, 240);
	private final Color activeGreenColor = new Color(50, 205, 50);
	private final Color flashUpdateColor = new Color(0, 255, 255);
	private final Color hoverRowColor = new Color(225, 235, 245);

	private final Map<String, Long> lastUpdateTimestampMap = new ConcurrentHashMap<>();
	private int hoveredRowIndex = -1;

	private enum ViewState {
		VIEW_1, VIEW_2, VIEW_ALL
	}

	private ViewState currentViewState = ViewState.VIEW_1;
	private volatile float currentFadeAlpha = 0.0f;
	private boolean isFadingUp = true;
	private final Timer systemStatusFadeTimer;
	private volatile boolean isSirenStarted = false;
	private static final int AUDIO_SAMPLE_RATE = 44100;

	private final String[] tableColumns = { "Ticker", "Price", "Position", "Real PnL", "Unreal PnL", "Targets",
			"Stop Loss", "Budget", "Entry", "Time Exit", "Imp Move", "G-Flip", "D-VWAP", "RVOL", "Ratio 5/15",
			"Ratio 4/12", "ATR-15", "ATR-12", "ATR-5", "ATR-4", "ATR-D", "1m-Vol", "15AvgVol", "SMA200", "SMA100",
			"SMA50", "SMA20", "SMA10", "RSI", "L-Margin", "S-Margin", "Earnings" // Index 31
	};

	private final Set<Integer> view1Indices = Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 29, 30, 31);

	private final Set<Integer> view2Indices = Set.of(0, 1, 2, 3, 4, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
			31);

	public BlackboardMonitor(Blackboard blackboard, UniverseReference universeReference) {
		this.blackboard = blackboard;
		this.universeReference = universeReference;
		this.tableModel = new DefaultTableModel(tableColumns, 0);
		this.monitorTable = new JTable(tableModel);
		this.monitorTable.setRowHeight(25);

		// Crucial: Set resize mode to distribute space among all columns automatically
		this.monitorTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

		for (int index = 0; index < monitorTable.getColumnCount(); index++) {
			monitorTable.getColumnModel().getColumn(index).setCellRenderer(new FlashCellRenderer());
		}

		MouseAdapter mouseHoverAdapter = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent mouseEvent) {
				int rowIndex = monitorTable.rowAtPoint(mouseEvent.getPoint());
				if (rowIndex != hoveredRowIndex) {
					hoveredRowIndex = rowIndex;
					monitorTable.repaint();
				}
			}

			@Override
			public void mouseExited(MouseEvent mouseEvent) {
				hoveredRowIndex = -1;
				monitorTable.repaint();
			}
		};
		monitorTable.addMouseMotionListener(mouseHoverAdapter);
		monitorTable.addMouseListener(mouseHoverAdapter);

		headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		haltStatusLabel = new JLabel("", SwingConstants.LEFT);
		haltStatusLabel.setFont(new Font("Monospaced", Font.BOLD, 18));
		haltStatusLabel.setForeground(Color.BLUE);

		marketTimeLabel = new JLabel("Market Time: --:--:--", SwingConstants.RIGHT);
		marketTimeLabel.setFont(new Font("Monospaced", Font.BOLD, 18));

		updateRequiredLabel = new JLabel("", SwingConstants.CENTER);
		updateRequiredLabel.setFont(new Font("Monospaced", Font.BOLD, 18));
		updateRequiredLabel.setForeground(Color.BLUE);

		headerPanel.add(haltStatusLabel, BorderLayout.WEST);
		headerPanel.add(updateRequiredLabel, BorderLayout.CENTER);
		headerPanel.add(marketTimeLabel, BorderLayout.EAST);

		JPanel viewControlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		viewControlPanel.setBackground(defaultBackgroundGray);

		view1Button = new JButton("View 1");
		view2Button = new JButton("View 2");
		viewAllButton = new JButton("View All");

		view1Button.addActionListener(actionEvent -> updateView(ViewState.VIEW_1));
		view2Button.addActionListener(actionEvent -> updateView(ViewState.VIEW_2));
		viewAllButton.addActionListener(actionEvent -> updateView(ViewState.VIEW_ALL));

		viewControlPanel.add(view1Button);
		viewControlPanel.add(view2Button);
		viewControlPanel.add(viewAllButton);

		JPanel accountPanel = new JPanel(new GridLayout(2, 5, 15, 5));
		accountPanel.setBorder(BorderFactory.createTitledBorder("Account Portfolio"));
		accountPanel.setBackground(defaultBackgroundGray);

		addAccountLabel(accountPanel, "Net Liquidation: ", netLiquidationValueLabel);
		addAccountLabel(accountPanel, "Total Cash: ", totalCashValueLabel);
		addAccountLabel(accountPanel, "Settled Cash: ", settledCashValueLabel);
		addAccountLabel(accountPanel, "Buying Power: ", buyingPowerValueLabel);
		addAccountLabel(accountPanel, "Avail Funds: ", availableFundsValueLabel);
		addAccountLabel(accountPanel, "Realized PnL: ", realizedPnLValueLabel);
		addAccountLabel(accountPanel, "Unrealized PnL: ", unrealizedPnLValueLabel);
		addAccountLabel(accountPanel, "Excess Margin: ", excessMarginValueLabel);
		addAccountLabel(accountPanel, "Cushion: ", marginCushionValueLabel);

		JPanel footerPanel = new JPanel(new BorderLayout());
		footerPanel.add(viewControlPanel, BorderLayout.NORTH);
		footerPanel.add(accountPanel, BorderLayout.SOUTH);

		this.setTitle("Raptor Strategy Monitor - Proportional View Layout");
		this.setLayout(new BorderLayout());
		this.add(headerPanel, BorderLayout.NORTH);
		this.add(new JScrollPane(monitorTable), BorderLayout.CENTER);
		this.add(footerPanel, BorderLayout.SOUTH);
		this.setSize(1650, 1000);
		this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		updateView(ViewState.VIEW_1);
		this.setVisible(true);

		systemStatusFadeTimer = new Timer(50, actionEvent -> performSystemStatusFade());
		systemStatusFadeTimer.start();

		Timer animationRepaintTimer = new Timer(33, actionEvent -> monitorTable.repaint());
		animationRepaintTimer.start();

		startDataRefreshThread();
	}

	private void updateView(ViewState targetViewState) {
		this.currentViewState = targetViewState;
		applyColumnVisibility();
		updateButtonStyles();
	}

	private void applyColumnVisibility() {
		for (int index = 0; index < tableColumns.length; index++) {
			TableColumn column = monitorTable.getColumnModel().getColumn(index);
			boolean isVisible = false;

			switch (currentViewState) {
			case VIEW_1 -> isVisible = view1Indices.contains(index);
			case VIEW_2 -> isVisible = view2Indices.contains(index);
			case VIEW_ALL -> isVisible = true;
			}

			if (isVisible) {
				column.setMinWidth(10);
				column.setMaxWidth(Integer.MAX_VALUE);
				column.setPreferredWidth(100);
			} else {
				column.setMinWidth(0);
				column.setMaxWidth(0);
				column.setPreferredWidth(0);
			}
		}
		monitorTable.doLayout();
	}

	private void updateButtonStyles() {
		view1Button.setBackground(null);
		view2Button.setBackground(null);
		viewAllButton.setBackground(null);

		switch (currentViewState) {
		case VIEW_1 -> view1Button.setBackground(activeGreenColor);
		case VIEW_2 -> view2Button.setBackground(activeGreenColor);
		case VIEW_ALL -> viewAllButton.setBackground(activeGreenColor);
		}
	}

	private void addAccountLabel(JPanel panel, String title, JLabel valueLabel) {
		JPanel container = new JPanel(new BorderLayout());
		container.setBackground(defaultBackgroundGray);
		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
		valueLabel.setFont(new Font("Monospaced", Font.PLAIN, 14));
		container.add(titleLabel, BorderLayout.WEST);
		container.add(valueLabel, BorderLayout.CENTER);
		panel.add(container);
	}

	private void performSystemStatusFade() {
		boolean isSystemHalted = blackboard.getSystemHalted();
		boolean isUpdateRequired = blackboard.getSystemUpdateRequired();

		if (isSystemHalted || isUpdateRequired) {
			if (isFadingUp) {
				currentFadeAlpha += 0.05f;
				if (currentFadeAlpha >= 1.0f) {
					currentFadeAlpha = 1.0f;
					isFadingUp = false;
				}
			} else {
				currentFadeAlpha -= 0.05f;
				if (currentFadeAlpha <= 0.0f) {
					currentFadeAlpha = 0.0f;
					isFadingUp = true;
				}
			}
			int redChannel = 255;
			int greenChannel = (int) (255 * (1.0f - currentFadeAlpha));
			int blueChannel = (int) (255 * (1.0f - currentFadeAlpha));

			Color alertColor = new Color(redChannel, greenChannel, blueChannel);
			headerPanel.setBackground(alertColor);
		} else {
			headerPanel.setBackground(defaultBackgroundGray);
			currentFadeAlpha = 0.0f;
			isFadingUp = true;
		}
	}

	private void startSiren() {
		isSirenStarted = true;
		Thread audioThread = new Thread(() -> {
			try {
				AudioFormat audioFormat = new AudioFormat(AUDIO_SAMPLE_RATE, 8, 1, true, false);
				SourceDataLine dataLine = AudioSystem.getSourceDataLine(audioFormat);
				dataLine.open(audioFormat);
				dataLine.start();

				byte[] audioBuffer = new byte[256];
				double oscillationPhase = 0;
				while (true) {
					float frequency = 400.0f + (currentFadeAlpha * 400.0f);
					double phaseIncrement = frequency / AUDIO_SAMPLE_RATE;
					for (int index = 0; index < audioBuffer.length; index++) {
						audioBuffer[index] = (byte) ((2.0 * oscillationPhase - 1.0) * 35);
						oscillationPhase += phaseIncrement;

						if (oscillationPhase >= 1.0)
							oscillationPhase -= 1.0;
					}
					dataLine.write(audioBuffer, 0, audioBuffer.length);
				}
			} catch (Exception exception) {
				exception.printStackTrace();
			}
		});
		audioThread.setDaemon(true);
		audioThread.start();
	}

	private void startDataRefreshThread() {
		Thread dataThread = new Thread(() -> {
			while (true) {
				try {
					updateDashboardData();
					Thread.sleep(refreshRateMilliseconds);
				} catch (InterruptedException interruptedException) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		});
		dataThread.setDaemon(true);
		dataThread.start();
	}

	private void updateDashboardData() {
		SwingUtilities.invokeLater(() -> {
			if ((blackboard.getSystemHalted() || blackboard.getSystemUpdateRequired()) && !isSirenStarted) {
				startSiren();
			}

			haltStatusLabel.setText(blackboard.getSystemHalted() ? "!!! SYSTEM HALTED !!!" : "");
			updateRequiredLabel.setText(blackboard.getSystemUpdateRequired() ? "UPDATE REQ" : "");

			Account account = blackboard.getAccount();
			netLiquidationValueLabel.setText(currencyFormatter.format(account.getNetLiquidation()));
			totalCashValueLabel.setText(currencyFormatter.format(account.getTotalCashValue()));
			settledCashValueLabel.setText(currencyFormatter.format(account.getSettledCash()));
			buyingPowerValueLabel.setText(currencyFormatter.format(account.getBuyingPower()));
			availableFundsValueLabel.setText(currencyFormatter.format(account.getAvailableFunds()));
			excessMarginValueLabel.setText(currencyFormatter.format(account.getExcessMargin()));
			updatePnLLabel(realizedPnLValueLabel, account.getRealizedPnL());
			updatePnLLabel(unrealizedPnLValueLabel, account.getUnrealizedPnL());

			marginCushionValueLabel.setText(percentFormatter.format(account.getCushion()));
			marginCushionValueLabel.setForeground(account.getCushion() < 0.05 ? Color.RED : Color.BLACK);

			long marketTimeEpoch = blackboard.getMarketTime();
			String formattedMarketTime = Instant.ofEpochMilli(marketTimeEpoch).atZone(ZoneId.of("America/New_York"))
					.format(timeFormatter);
			marketTimeLabel.setText("Market Time: " + formattedMarketTime);

			List<Stock> stockList = new ArrayList<>();
			blackboard.forEachStock(stockList::add);
			stockList.sort(Comparator.comparing(Stock::getTicker));

			if (tableModel.getRowCount() == 0) {
				for (Stock stock : stockList) {
					Vector<Object> rowVector = new Vector<>();
					for (int index = 0; index < tableColumns.length; index++)
						rowVector.add("");
					tableModel.addRow(rowVector);
				}
			}

			for (int rowIndex = 0; rowIndex < stockList.size(); rowIndex++) {
				Stock stock = stockList.get(rowIndex);
				BracketOrder activeBracket = stock.getActiveBracket();

				updateTableCellIfChanged(rowIndex, 0, stock.getTicker());
				updateTableCellIfChanged(rowIndex, 1, String.format("%.2f", stock.getLastPrice()));
				updateTableCellIfChanged(rowIndex, 2, stock.getPositionSize().toString());
				updateTableCellIfChanged(rowIndex, 3, String.format("%.2f", stock.getRealizedPnL()));
				updateTableCellIfChanged(rowIndex, 4, String.format("%.2f", stock.getUnrealizedPnl()));

				if (activeBracket != null) {
					List<BracketOrder.ExitSlice> bracketSlices = activeBracket.getSlices();
					StringBuilder targetStringBuilder = new StringBuilder("<html>");
					for (int sliceIndex = 0; sliceIndex < bracketSlices.size(); sliceIndex++) {
						BracketOrder.ExitSlice slice = bracketSlices.get(sliceIndex);
						if (slice.isFilled()) {
							targetStringBuilder.append("<strike>")
									.append(String.format("%.2f", slice.getTakeProfitPrice())).append("</strike>");
						} else {
							targetStringBuilder.append(String.format("%.2f", slice.getTakeProfitPrice()));
						}
						if (sliceIndex < bracketSlices.size() - 1)
							targetStringBuilder.append(" | ");
					}
					targetStringBuilder.append("</html>");
					updateTableCellIfChanged(rowIndex, 5, targetStringBuilder.toString());

					StringBuilder stopLossStringBuilder = new StringBuilder();
					for (int sliceIndex = 0; sliceIndex < bracketSlices.size(); sliceIndex++) {
						stopLossStringBuilder
								.append(String.format("%.2f", bracketSlices.get(sliceIndex).getStopLossPrice()));
						if (sliceIndex < bracketSlices.size() - 1)
							stopLossStringBuilder.append(" / ");
					}
					updateTableCellIfChanged(rowIndex, 6, stopLossStringBuilder.toString());

					int currentUpdateCount = activeBracket.getUpdateCount();
					int maxAllowedUpdates = bracketSlices.size() * 20;
					updateTableCellIfChanged(rowIndex, 7, currentUpdateCount + " / " + maxAllowedUpdates);

					updateTableCellIfChanged(rowIndex, 8, String.format("%.2f", activeBracket.getEntryPrice()));

					long timeExitEpoch = 0;
					for (BracketOrder.ExitSlice slice : bracketSlices) {
						if (!slice.isFilled()) {
							timeExitEpoch = slice.getTimeExit();
							break;
						}
					}
					String timeExitString = timeExitEpoch > 0 ? Instant.ofEpochMilli(timeExitEpoch)
							.atZone(ZoneId.of("America/New_York")).format(timeFormatter) : "";
					updateTableCellIfChanged(rowIndex, 9, timeExitString);

				} else {
					for (int columnIndex = 5; columnIndex <= 9; columnIndex++)
						updateTableCellIfChanged(rowIndex, columnIndex, "");
				}

				updateTableCellIfChanged(rowIndex, 10, String.format("%.2f", stock.getDailyImpliedMove()));
				updateTableCellIfChanged(rowIndex, 11, String.format("%.2f", stock.getGammaFlip()));
				updateTableCellIfChanged(rowIndex, 12, String.format("%.2f", stock.getDailyVWAP()));

				double averageVolume = stock.getAverageLast15MinuteVolume().value().doubleValue();
				double relativeVolume = (averageVolume > 0)
						? (stock.getLastMinuteVolume().value().doubleValue() / averageVolume)
						: 0.0;
				updateTableCellIfChanged(rowIndex, 13, String.format("%.2f", relativeVolume));

				double ratio515 = stock.getIntradayATR15() > 0 ? stock.getIntradayATR5() / stock.getIntradayATR15()
						: 0.0;
				double ratio412 = stock.getIntradayATR12() > 0 ? stock.getIntradayATR4() / stock.getIntradayATR12()
						: 0.0;

				updateTableCellIfChanged(rowIndex, 14, String.format("%.2f", ratio515));
				updateTableCellIfChanged(rowIndex, 15, String.format("%.2f", ratio412));

				updateTableCellIfChanged(rowIndex, 16, String.format("%.2f", stock.getIntradayATR15()));
				updateTableCellIfChanged(rowIndex, 17, String.format("%.2f", stock.getIntradayATR12()));
				updateTableCellIfChanged(rowIndex, 18, String.format("%.2f", stock.getIntradayATR5()));
				updateTableCellIfChanged(rowIndex, 19, String.format("%.2f", stock.getIntradayATR4()));
				updateTableCellIfChanged(rowIndex, 20, String.format("%.2f", stock.getDailyATR()));
				updateTableCellIfChanged(rowIndex, 21, stock.getLastMinuteVolume().toString());
				updateTableCellIfChanged(rowIndex, 22, stock.getAverageLast15MinuteVolume().toString());
				updateTableCellIfChanged(rowIndex, 23, String.format("%.2f", stock.getSma200()));
				updateTableCellIfChanged(rowIndex, 24, String.format("%.2f", stock.getSma100()));
				updateTableCellIfChanged(rowIndex, 25, String.format("%.2f", stock.getSma50()));
				updateTableCellIfChanged(rowIndex, 26, String.format("%.2f", stock.getSma20()));
				updateTableCellIfChanged(rowIndex, 27, String.format("%.2f", stock.getSma10()));
				updateTableCellIfChanged(rowIndex, 28, String.format("%.2f", stock.getRSI()));
				updateTableCellIfChanged(rowIndex, 29, String.format("%.2f",
						universeReference.marginRate(stock.getTicker(), true)));
				updateTableCellIfChanged(rowIndex, 30, String.format("%.2f",
						universeReference.marginRate(stock.getTicker(), false)));

				// Formatter for Earnings
				String earningsStr = "";
				Instant earningsInstant = stock.getNextEarningsDate();
				if (earningsInstant != null) {
					earningsStr = earningsInstant.atZone(ZoneId.of("America/New_York")).format(earningsFormatter);
				}
				updateTableCellIfChanged(rowIndex, 31, earningsStr);
			}
		});
	}

	private void updatePnLLabel(JLabel label, double value) {
		label.setText(currencyFormatter.format(value));
		label.setForeground(value >= 0 ? new Color(0, 128, 0) : Color.RED);
	}

	private void updateTableCellIfChanged(int rowIndex, int columnIndex, Object newValue) {
		Object oldValue = tableModel.getValueAt(rowIndex, columnIndex);
		if (oldValue == null || !oldValue.equals(newValue)) {
			tableModel.setValueAt(newValue, rowIndex, columnIndex);
			String stockTicker = (String) tableModel.getValueAt(rowIndex, 0);
			lastUpdateTimestampMap.put(stockTicker + ":" + columnIndex, System.currentTimeMillis());
		}
	}

	private class FlashCellRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
				int rowIndex, int columnIndex) {
			Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, rowIndex,
					columnIndex);

			String stockTicker = (String) table.getValueAt(rowIndex, 0);
			Long lastUpdateTimestamp = lastUpdateTimestampMap.get(stockTicker + ":" + columnIndex);
			long currentTime = System.currentTimeMillis();

			if (columnIndex == 3 || columnIndex == 4) {
				try {
					double pnlValue = Double.parseDouble(value.toString());
					component.setForeground(pnlValue >= 0 ? new Color(0, 128, 0) : Color.RED);
				} catch (Exception exception) {
					component.setForeground(Color.BLACK);
				}
			} else {
				component.setForeground(Color.BLACK);
			}

			long flashDuration = 250;
			long halfDuration = flashDuration / 2;
			Color baseBackgroundColor = (rowIndex == hoveredRowIndex) ? hoverRowColor : Color.WHITE;

			if (lastUpdateTimestamp != null && (currentTime - lastUpdateTimestamp) < flashDuration) {
				long elapsedTime = currentTime - lastUpdateTimestamp;
				float colorRatio = (elapsedTime < halfDuration) ? (elapsedTime / (float) halfDuration)
						: (1.0f - ((elapsedTime - halfDuration) / (float) halfDuration));

				component.setBackground(interpolateColor(baseBackgroundColor, flashUpdateColor, colorRatio));
			} else {
				component.setBackground(baseBackgroundColor);
			}
			return component;
		}

		private Color interpolateColor(Color startColor, Color endColor, float ratio) {
			int red = (int) (startColor.getRed() + ratio * (endColor.getRed() - startColor.getRed()));
			int green = (int) (startColor.getGreen() + ratio * (endColor.getGreen() - startColor.getGreen()));
			int blue = (int) (startColor.getBlue() + ratio * (endColor.getBlue() - startColor.getBlue()));
			return new Color(red, green, blue);
		}
	}
}
