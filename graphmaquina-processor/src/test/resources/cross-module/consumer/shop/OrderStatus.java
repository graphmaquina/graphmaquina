package shop;

/** The states of an {@link Order}: a paid order waits for its items. */
public enum OrderStatus {
    NEW,
    PAID,
    SHIPPED
}
