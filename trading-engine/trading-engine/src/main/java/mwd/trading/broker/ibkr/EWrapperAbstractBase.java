package mwd.trading.broker.ibkr;

import com.ib.client.*;
import com.ib.client.protobuf.AccountDataEndProto.AccountDataEnd;
import com.ib.client.protobuf.AccountSummaryEndProto.AccountSummaryEnd;
import com.ib.client.protobuf.AccountSummaryProto.AccountSummary;
import com.ib.client.protobuf.AccountUpdateMultiEndProto.AccountUpdateMultiEnd;
import com.ib.client.protobuf.AccountUpdateMultiProto.AccountUpdateMulti;
import com.ib.client.protobuf.AccountUpdateTimeProto.AccountUpdateTime;
import com.ib.client.protobuf.AccountValueProto.AccountValue;
import com.ib.client.protobuf.CompletedOrderProto.CompletedOrder;
import com.ib.client.protobuf.CompletedOrdersEndProto.CompletedOrdersEnd;
import com.ib.client.protobuf.ConfigResponseProto.ConfigResponse;
import com.ib.client.protobuf.ContractDataEndProto.ContractDataEnd;
import com.ib.client.protobuf.ContractDataProto.ContractData;
import com.ib.client.protobuf.CurrentTimeInMillisProto.CurrentTimeInMillis;
import com.ib.client.protobuf.CurrentTimeProto.CurrentTime;
import com.ib.client.protobuf.DisplayGroupListProto.DisplayGroupList;
import com.ib.client.protobuf.DisplayGroupUpdatedProto.DisplayGroupUpdated;
import com.ib.client.protobuf.ErrorMessageProto.ErrorMessage;
import com.ib.client.protobuf.ExecutionDetailsEndProto.ExecutionDetailsEnd;
import com.ib.client.protobuf.ExecutionDetailsProto.ExecutionDetails;
import com.ib.client.protobuf.FamilyCodesProto.FamilyCodes;
import com.ib.client.protobuf.FundamentalsDataProto.FundamentalsData;
import com.ib.client.protobuf.HeadTimestampProto.HeadTimestamp;
import com.ib.client.protobuf.HistogramDataProto.HistogramData;
import com.ib.client.protobuf.HistoricalDataEndProto.HistoricalDataEnd;
import com.ib.client.protobuf.HistoricalDataProto.HistoricalData;
import com.ib.client.protobuf.HistoricalDataUpdateProto.HistoricalDataUpdate;
import com.ib.client.protobuf.HistoricalNewsEndProto.HistoricalNewsEnd;
import com.ib.client.protobuf.HistoricalNewsProto.HistoricalNews;
import com.ib.client.protobuf.HistoricalScheduleProto.HistoricalSchedule;
import com.ib.client.protobuf.HistoricalTicksBidAskProto.HistoricalTicksBidAsk;
import com.ib.client.protobuf.HistoricalTicksLastProto.HistoricalTicksLast;
import com.ib.client.protobuf.HistoricalTicksProto.HistoricalTicks;
import com.ib.client.protobuf.ManagedAccountsProto.ManagedAccounts;
import com.ib.client.protobuf.MarketDataTypeProto.MarketDataType;
import com.ib.client.protobuf.MarketDepthExchangesProto.MarketDepthExchanges;
import com.ib.client.protobuf.MarketDepthL2Proto.MarketDepthL2;
import com.ib.client.protobuf.MarketDepthProto.MarketDepth;
import com.ib.client.protobuf.MarketRuleProto.MarketRule;
import com.ib.client.protobuf.NewsArticleProto.NewsArticle;
import com.ib.client.protobuf.NewsBulletinProto.NewsBulletin;
import com.ib.client.protobuf.NewsProvidersProto.NewsProviders;
import com.ib.client.protobuf.NextValidIdProto.NextValidId;
import com.ib.client.protobuf.OpenOrderProto.OpenOrder;
import com.ib.client.protobuf.OpenOrdersEndProto.OpenOrdersEnd;
import com.ib.client.protobuf.OrderBoundProto.OrderBound;
import com.ib.client.protobuf.OrderStatusProto.OrderStatus;
import com.ib.client.protobuf.PnLProto.PnL;
import com.ib.client.protobuf.PnLSingleProto.PnLSingle;
import com.ib.client.protobuf.PortfolioValueProto.PortfolioValue;
import com.ib.client.protobuf.PositionEndProto.PositionEnd;
import com.ib.client.protobuf.PositionMultiEndProto.PositionMultiEnd;
import com.ib.client.protobuf.PositionMultiProto.PositionMulti;
import com.ib.client.protobuf.PositionProto.Position;
import com.ib.client.protobuf.RealTimeBarTickProto.RealTimeBarTick;
import com.ib.client.protobuf.ReceiveFAProto.ReceiveFA;
import com.ib.client.protobuf.ReplaceFAEndProto.ReplaceFAEnd;
import com.ib.client.protobuf.RerouteMarketDataRequestProto.RerouteMarketDataRequest;
import com.ib.client.protobuf.RerouteMarketDepthRequestProto.RerouteMarketDepthRequest;
import com.ib.client.protobuf.ScannerDataProto.ScannerData;
import com.ib.client.protobuf.ScannerParametersProto.ScannerParameters;
import com.ib.client.protobuf.SecDefOptParameterEndProto.SecDefOptParameterEnd;
import com.ib.client.protobuf.SecDefOptParameterProto.SecDefOptParameter;
import com.ib.client.protobuf.SmartComponentsProto.SmartComponents;
import com.ib.client.protobuf.SoftDollarTiersProto.SoftDollarTiers;
import com.ib.client.protobuf.SymbolSamplesProto.SymbolSamples;
import com.ib.client.protobuf.TickByTickDataProto.TickByTickData;
import com.ib.client.protobuf.TickGenericProto.TickGeneric;
import com.ib.client.protobuf.TickNewsProto.TickNews;
import com.ib.client.protobuf.TickOptionComputationProto.TickOptionComputation;
import com.ib.client.protobuf.TickPriceProto.TickPrice;
import com.ib.client.protobuf.TickReqParamsProto.TickReqParams;
import com.ib.client.protobuf.TickSizeProto.TickSize;
import com.ib.client.protobuf.TickSnapshotEndProto.TickSnapshotEnd;
import com.ib.client.protobuf.TickStringProto.TickString;
import com.ib.client.protobuf.UpdateConfigResponseProto.UpdateConfigResponse;
import com.ib.client.protobuf.UserInfoProto.UserInfo;
import com.ib.client.protobuf.VerifyCompletedProto.VerifyCompleted;
import com.ib.client.protobuf.VerifyMessageApiProto.VerifyMessageApi;
import com.ib.client.protobuf.WshEventDataProto.WshEventData;
import com.ib.client.protobuf.WshMetaDataProto.WshMetaData;

public abstract class EWrapperAbstractBase implements EWrapper {
    @Override public void tickPrice(int reqId, int field, double price, TickAttrib attribs) {}
    @Override public void tickSize(int reqId, int field, Decimal size) {}
    @Override public void tickOptionComputation(int reqId, int field, int tickAttrib, double impliedVol, double delta, double optPrice, double pvDividend, double gamma, double vega, double theta, double undPrice) {}
    @Override public void tickGeneric(int reqId, int tickType, double value) {}
    @Override public void tickString(int reqId, int tickType, String value) {}
    @Override public void tickEFP(int reqId, int tickType, double basisPoints, String formattedBasisPoints, double impliedFuture, int holdDays, String futureExpiry, double dividendImpact, double dividendsToExpiry) {}
    @Override public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {}
    @Override public void openOrderEnd() {}
    @Override public void updateAccountValue(String key, String value, String currency, String accountName) {}
    @Override public void updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue, double averageCost, double unrealizedPNL, double realizedPNL, String accountName) {}
    @Override public void updateAccountTime(String timeStamp) {}
    @Override public void accountDownloadEnd(String accountName) {}
    @Override public void nextValidId(int orderId) {}
    @Override public void contractDetails(int reqId, ContractDetails contractDetails) {}
    @Override public void bondContractDetails(int reqId, ContractDetails contractDetails) {}
    @Override public void contractDetailsEnd(int reqId) {}
    @Override public void execDetails(int reqId, Contract contract, Execution execution) {}
    @Override public void execDetailsEnd(int reqId) {}
    @Override public void updateMktDepth(int reqId, int position, int operation, int side, double price, Decimal size) {}
    @Override public void updateMktDepthL2(int reqId, int position, String marketMaker, int operation, int side, double price, Decimal size, boolean isSmartDepth) {}
    @Override public void updateNewsBulletin(int msgId, int msgType, String message, String origExchange) {}
    @Override public void managedAccounts(String accountsList) {}
    @Override public void receiveFA(int faDataType, String xml) {}
    @Override public void historicalData(int reqId, Bar bar) {}
    @Override public void scannerParameters(String xml) {}
    @Override public void scannerData(int reqId, int rank, ContractDetails contractDetails, String distance, String benchmark, String projection, String legsStr) {}
    @Override public void scannerDataEnd(int reqId) {}
    @Override public void realtimeBar(int reqId, long time, double open, double high, double low, double close, Decimal volume, Decimal wap, int count) {}
    @Override public void currentTime(long time) {}
    @Override public void fundamentalData(int reqId, String data) {}
    @Override public void deltaNeutralValidation(int reqId, DeltaNeutralContract deltaNeutralContract) {}
    @Override public void tickSnapshotEnd(int reqId) {}
    @Override public void marketDataType(int reqId, int marketDataType) {}
    @Override public void position(String account, Contract contract, Decimal pos, double avgCost) {}
    @Override public void positionEnd() {}
    @Override public void accountSummary(int reqId, String account, String tag, String value, String currency) {}
    @Override public void accountSummaryEnd(int reqId) {}
    @Override public void verifyMessageAPI(String apiData) {}
    @Override public void verifyCompleted(boolean isSuccessful, String errorText) {}
    @Override public void verifyAndAuthMessageAPI(String apiData, String xyzChallenge) {}
    @Override public void verifyAndAuthCompleted(boolean isSuccessful, String errorText) {}
    @Override public void displayGroupList(int reqId, String groups) {}
    @Override public void displayGroupUpdated(int reqId, String contractInfo) {}
    @Override public void error(Exception e) {}
    @Override public void error(String str) {}
    @Override public void connectionClosed() {}
    @Override public void connectAck() {}
    @Override public void positionMulti(int reqId, String account, String modelCode, Contract contract, Decimal pos, double avgCost) {}
    @Override public void positionMultiEnd(int reqId) {}
    @Override public void accountUpdateMulti(int reqId, String account, String modelCode, String key, String value, String currency) {}
    @Override public void accountUpdateMultiEnd(int reqId) {}
    @Override public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, java.util.Set<String> expirations, java.util.Set<Double> strikes) {}
    @Override public void securityDefinitionOptionalParameterEnd(int reqId) {}
    @Override public void softDollarTiers(int reqId, SoftDollarTier[] tiers) {}
    @Override public void familyCodes(FamilyCode[] familyCodes) {}
    @Override public void symbolSamples(int reqId, ContractDescription[] contractDescriptions) {}
    @Override public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {}
    @Override public void mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions) {}
    @Override public void tickNews(int reqId, long time, String providerCode, String articleId, String headline, String extraData) {}
    @Override public void smartComponents(int reqId, java.util.Map<Integer, java.util.Map.Entry<String, Character>> theMap) {}
    @Override public void tickReqParams(int reqId, double minTick, String bboExchange, int snapshotPermissions) {}
    @Override public void newsProviders(NewsProvider[] newsProviders) {}
    @Override public void newsArticle(int requestId, int articleType, String articleText) {}
    @Override public void historicalNews(int requestId, String time, String providerCode, String articleId, String headline) {}
    @Override public void historicalNewsEnd(int requestId, boolean hasMore) {}
    @Override public void headTimestamp(int reqId, String headTimestamp) {}
    @Override public void histogramData(int reqId, java.util.List<HistogramEntry> items) {}
    @Override public void historicalDataUpdate(int reqId, Bar bar) {}
    @Override public void rerouteMktDataReq(int reqId, int conId, String exchange) {}
    @Override public void rerouteMktDepthReq(int reqId, int conId, String exchange) {}
    @Override public void marketRule(int marketRuleId, PriceIncrement[] priceIncrements) {}
    @Override public void pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL) {}
    @Override public void pnlSingle(int reqId, Decimal pos, double dailyPnL, double unrealizedPnL, double realizedPnL, double value) {}
    @Override public void historicalTicks(int reqId, java.util.List<HistoricalTick> ticks, boolean done) {}
    @Override public void historicalTicksBidAsk(int reqId, java.util.List<HistoricalTickBidAsk> ticks, boolean done) {}
    @Override public void historicalTicksLast(int reqId, java.util.List<HistoricalTickLast> ticks, boolean done) {}
    @Override public void tickByTickAllLast(int reqId, int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast, String exchange, String specialConditions) {}
    @Override public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize, Decimal askSize, TickAttribBidAsk tickAttribBidAsk) {}
    @Override public void tickByTickMidPoint(int reqId, long time, double midPoint) {}
    @Override public void orderBound(long orderId, int apiClientId, int apiOrderId) {}
    @Override public void completedOrder(Contract contract, Order order, OrderState orderState) {}
    @Override public void completedOrdersEnd() {}
    @Override public void replaceFAEnd(int reqId, String text) {}
    @Override public void wshMetaData(int reqId, String dataJson) {}
    @Override public void wshEventData(int reqId, String dataJson) {}
    @Override public void historicalSchedule(int reqId, String startDateTime, String endDateTime, String timeZone, java.util.List<HistoricalSession> sessions) {}
    @Override public void userInfo(int reqId, String userInfo) {}
    
    // --- ProtoBuf ---
    @Override public void accountDataEndProtoBuf(AccountDataEnd arg0) {}
    @Override public void accountSummaryEndProtoBuf(AccountSummaryEnd arg0) {}
    @Override public void accountSummaryProtoBuf(AccountSummary arg0) {}
    @Override public void accountUpdateMultiEndProtoBuf(AccountUpdateMultiEnd arg0) {}
    @Override public void accountUpdateMultiProtoBuf(AccountUpdateMulti arg0) {}
    @Override public void bondContractDataProtoBuf(ContractData arg0) {}
    @Override public void commissionAndFeesReport(CommissionAndFeesReport arg0) {}
    @Override public void commissionAndFeesReportProtoBuf(com.ib.client.protobuf.CommissionAndFeesReportProto.CommissionAndFeesReport arg0) {}
    @Override public void completedOrderProtoBuf(CompletedOrder arg0) {}
    @Override public void completedOrdersEndProtoBuf(CompletedOrdersEnd arg0) {}
    @Override public void configResponseProtoBuf(ConfigResponse arg0) {}
    @Override public void contractDataEndProtoBuf(ContractDataEnd arg0) {}
    @Override public void contractDataProtoBuf(ContractData arg0) {}
    @Override public void currentTimeInMillis(long arg0) {}
    @Override public void currentTimeInMillisProtoBuf(CurrentTimeInMillis arg0) {}
    @Override public void currentTimeProtoBuf(CurrentTime arg0) {}
    @Override public void displayGroupListProtoBuf(DisplayGroupList arg0) {}
    @Override public void displayGroupUpdatedProtoBuf(DisplayGroupUpdated arg0) {}
    @Override public void error(int arg0, long arg1, int arg2, String arg3, String arg4) {}
    @Override public void errorProtoBuf(ErrorMessage arg0) {}
    @Override public void execDetailsEndProtoBuf(ExecutionDetailsEnd arg0) {}
    @Override public void execDetailsProtoBuf(ExecutionDetails arg0) {}
    @Override public void familyCodesProtoBuf(FamilyCodes arg0) {}
    @Override public void fundamentalsDataProtoBuf(FundamentalsData arg0) {}
    @Override public void headTimestampProtoBuf(HeadTimestamp arg0) {}
    @Override public void histogramDataProtoBuf(HistogramData arg0) {}
    @Override public void historicalDataEndProtoBuf(HistoricalDataEnd arg0) {}
    @Override public void historicalDataProtoBuf(HistoricalData arg0) {}
    @Override public void historicalDataUpdateProtoBuf(HistoricalDataUpdate arg0) {}
    @Override public void historicalNewsEndProtoBuf(HistoricalNewsEnd arg0) {}
    @Override public void historicalNewsProtoBuf(HistoricalNews arg0) {}
    @Override public void historicalScheduleProtoBuf(HistoricalSchedule arg0) {}
    @Override public void historicalTicksBidAskProtoBuf(HistoricalTicksBidAsk arg0) {}
    @Override public void historicalTicksLastProtoBuf(HistoricalTicksLast arg0) {}
    @Override public void historicalTicksProtoBuf(HistoricalTicks arg0) {}
    @Override public void managedAccountsProtoBuf(ManagedAccounts arg0) {}
    @Override public void marketDataTypeProtoBuf(MarketDataType arg0) {}
    @Override public void marketDepthExchangesProtoBuf(MarketDepthExchanges arg0) {}
    @Override public void marketRuleProtoBuf(MarketRule arg0) {}
    @Override public void newsArticleProtoBuf(NewsArticle arg0) {}
    @Override public void newsProvidersProtoBuf(NewsProviders arg0) {}
    @Override public void nextValidIdProtoBuf(NextValidId arg0) {}
    @Override public void openOrderProtoBuf(OpenOrder arg0) {}
    @Override public void openOrdersEndProtoBuf(OpenOrdersEnd arg0) {}
    @Override public void orderBoundProtoBuf(OrderBound arg0) {}
    @Override public void orderStatus(int arg0, String arg1, Decimal arg2, Decimal arg3, double arg4, long arg5, int arg6, double arg7, int arg8, String arg9, double arg10) {}
    @Override public void orderStatusProtoBuf(OrderStatus arg0) {}
    @Override public void pnlProtoBuf(PnL arg0) {}
    @Override public void pnlSingleProtoBuf(PnLSingle arg0) {}
    @Override public void positionEndProtoBuf(PositionEnd arg0) {}
    @Override public void positionMultiEndProtoBuf(PositionMultiEnd arg0) {}
    @Override public void positionMultiProtoBuf(PositionMulti arg0) {}
    @Override public void positionProtoBuf(Position arg0) {}
    @Override public void realTimeBarTickProtoBuf(RealTimeBarTick arg0) {}
    @Override public void receiveFAProtoBuf(ReceiveFA arg0) {}
    @Override public void replaceFAEndProtoBuf(ReplaceFAEnd arg0) {}
    @Override public void rerouteMarketDataRequestProtoBuf(RerouteMarketDataRequest arg0) {}
    @Override public void rerouteMarketDepthRequestProtoBuf(RerouteMarketDepthRequest arg0) {}
    @Override public void scannerDataProtoBuf(ScannerData arg0) {}
    @Override public void scannerParametersProtoBuf(ScannerParameters arg0) {}
    @Override public void secDefOptParameterEndProtoBuf(SecDefOptParameterEnd arg0) {}
    @Override public void secDefOptParameterProtoBuf(SecDefOptParameter arg0) {}
    @Override public void smartComponentsProtoBuf(SmartComponents arg0) {}
    @Override public void softDollarTiersProtoBuf(SoftDollarTiers arg0) {}
    @Override public void symbolSamplesProtoBuf(SymbolSamples arg0) {}
    @Override public void tickByTickDataProtoBuf(TickByTickData arg0) {}
    @Override public void tickGenericProtoBuf(TickGeneric arg0) {}
    @Override public void tickNewsProtoBuf(TickNews arg0) {}
    @Override public void tickOptionComputationProtoBuf(TickOptionComputation arg0) {}
    @Override public void tickPriceProtoBuf(TickPrice arg0) {}
    @Override public void tickReqParamsProtoBuf(TickReqParams arg0) {}
    @Override public void tickSizeProtoBuf(TickSize arg0) {}
    @Override public void tickSnapshotEndProtoBuf(TickSnapshotEnd arg0) {}
    @Override public void tickStringProtoBuf(TickString arg0) {}
    @Override public void updateAccountTimeProtoBuf(AccountUpdateTime arg0) {}
    @Override public void updateAccountValueProtoBuf(AccountValue arg0) {}
    @Override public void updateConfigResponseProtoBuf(UpdateConfigResponse arg0) {}
    @Override public void updateMarketDepthL2ProtoBuf(MarketDepthL2 arg0) {}
    @Override public void updateMarketDepthProtoBuf(MarketDepth arg0) {}
    @Override public void updateNewsBulletinProtoBuf(NewsBulletin arg0) {}
    @Override public void updatePortfolioProtoBuf(PortfolioValue arg0) {}
    @Override public void userInfoProtoBuf(UserInfo arg0) {}
    @Override public void verifyCompletedProtoBuf(VerifyCompleted arg0) {}
    @Override public void verifyMessageApiProtoBuf(VerifyMessageApi arg0) {}
    @Override public void wshEventDataProtoBuf(WshEventData arg0) {}
    @Override public void wshMetaDataProtoBuf(WshMetaData arg0) {}
}