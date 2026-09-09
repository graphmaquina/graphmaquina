package io.github.graphmaquina.example.order;

/** States of an Order, from a fresh order through payment to a terminal shipment or cancellation. */
public enum OrderStatus { NEW, PAID, PAYMENT_DECLINED, SHIPPED, CANCELLED }
