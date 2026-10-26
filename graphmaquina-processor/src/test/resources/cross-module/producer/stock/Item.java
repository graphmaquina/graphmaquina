package stock;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Objects;

/** An item, the entity of {@link Items}. It names no order, nor anything else a consumer groups it in. */
public final class Item {

    private final String id;

    @GraphProperty
    private ItemStatus status = ItemStatus.OPEN;

    /** An open item identified by {@code id}. */
    public Item(String id) {
        this.id = Objects.requireNonNull(id);
    }

    @GraphProperty
    public String id() {
        return id;
    }

    public ItemStatus status() {
        return status;
    }

    public void status(ItemStatus value) {
        status = Objects.requireNonNull(value);
    }
}
