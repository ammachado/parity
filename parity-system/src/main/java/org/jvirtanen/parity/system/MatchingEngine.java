package org.jvirtanen.parity.system;

import static org.jvirtanen.parity.util.Strings.*;

import it.unimi.dsi.fastutil.longs.Long2ObjectArrayMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import org.jvirtanen.parity.match.Market;
import org.jvirtanen.parity.match.MarketListener;
import org.jvirtanen.parity.match.Side;
import org.jvirtanen.parity.net.pmd.PMD;
import org.jvirtanen.parity.net.poe.POE;

class MatchingEngine {

    private Long2ObjectArrayMap<Market>   markets;
    private Long2ObjectOpenHashMap<Order> orders;

    private MarketDataServer marketData;

    private long nextOrderNumber;
    private long nextMatchNumber;

    private Order handling;

    private long instrument;

    public MatchingEngine(List<String> instruments, MarketDataServer marketData) {
        this.markets = new Long2ObjectArrayMap<>();
        this.orders  = new Long2ObjectOpenHashMap<>();

        EventHandler handler = new EventHandler();

        for (String instrument : instruments)
            markets.put(encodeLong(instrument), new Market(handler));

        this.marketData = marketData;

        this.nextOrderNumber = 1;
        this.nextMatchNumber = 1;
    }

    public void enterOrder(POE.EnterOrder message, Session session) {
        Market market = markets.get(message.instrument);
        if (market == null) {
            session.orderRejected(message, POE.ORDER_REJECT_REASON_UNKNOWN_INSTRUMENT);
            return;
        }

        long orderNumber = nextOrderNumber++;

        handling = new Order(message.orderId, orderNumber, session, market);

        instrument = message.instrument;

        session.orderAccepted(message, handling);

        market.enter(orderNumber, side(message.side), message.price, message.quantity);
    }

    public void cancelOrder(POE.CancelOrder message, Order order) {
        handling = order;

        order.getMarket().cancel(order.getOrderNumber(), message.quantity);
    }

    public void cancel(Order order) {
        handling = null;

        order.getMarket().cancel(order.getOrderNumber(), 0);

        orders.remove(order.getOrderNumber());
    }

    private void track(Order order) {
        orders.put(order.getOrderNumber(), order);

        order.getSession().track(order);
    }

    private void release(Order order) {
        order.getSession().release(order);

        orders.remove(order.getOrderNumber());
    }

    private class EventHandler implements MarketListener {

        @Override
        public void match(long restingOrderNumber, long incomingOrderNumber, long price,
                long executedQuantity, long remainingQuantity) {
            Order resting = orders.get(restingOrderNumber);
            if (resting == null)
                return;

            long matchNumber = nextMatchNumber++;

            resting.getSession().orderExecuted(price, executedQuantity, POE.LIQUIDITY_FLAG_ADDED_LIQUIDITY,
                    matchNumber, resting);

            handling.getSession().orderExecuted(price, executedQuantity, POE.LIQUIDITY_FLAG_REMOVED_LIQUIDITY,
                    matchNumber, handling);

            marketData.orderExecuted(resting.getOrderNumber(), executedQuantity, matchNumber);

            if (remainingQuantity == 0)
                release(resting);
        }

        @Override
        public void add(long orderNumber, Side side, long price, long size) {
            marketData.orderAdded(orderNumber, side(side), instrument, size, price);

            track(handling);
        }

        @Override
        public void cancel(long orderNumber, long canceledQuantity, long remainingQuantity) {
            if (handling != null)
                handling.getSession().orderCanceled(canceledQuantity, POE.ORDER_CANCEL_REASON_REQUEST, handling);

            if (remainingQuantity > 0) {
                marketData.orderCanceled(orderNumber, canceledQuantity);
            } else {
                marketData.orderDeleted(orderNumber);

                if (remainingQuantity == 0 && handling != null)
                    release(handling);
            }
        }

    }

    private Side side(byte value) {
        switch (value) {
        case POE.BUY:
            return Side.BUY;
        case POE.SELL:
            return Side.SELL;
        }

        return null;
    }

    private byte side(Side value) {
        switch (value) {
        case BUY:
            return PMD.BUY;
        case SELL:
            return PMD.SELL;
        }

        return 0;
    }

}
