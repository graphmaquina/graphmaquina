# Compose machines across modules and JARs

A machine can watch, or bind as its sub-machine, a machine that another module compiled and published: a jar the
consumer receives, or the directory of classes of another module. The consumer declares the relation on its own side,
with `@GraphPeers`, exactly as for a machine of its own compilation. The pair's vocabulary stays typed, and the build
checks the relation against what the producer published, without the producer's sources. The machines still run in
one application, under one GraphMaquina runtime: separate artifacts add no remote call and no distributed
coordination.

This page follows two scenarios. A producer publishes a payment machine and an item machine and knows nothing of its
consumers. A consumer binds a payment as the deposit of a booking, and has an order watch its items. The
[scenario sources](../graphmaquina-processor/src/test/resources/cross-module) are compiled apart, the consumer
against the producer's jar alone, and run by
[CrossModuleCompositionTest](../graphmaquina-processor/src/test/java/io/github/graphmaquina/processor/CrossModuleCompositionTest.java);
every Java block below is a verbatim copy of them.

## Contents

- [What the producer publishes](#what-the-producer-publishes)
- [Bind a machine of a jar as a sub-machine](#bind-a-machine-of-a-jar-as-a-sub-machine)
- [Watch the machines of a jar](#watch-the-machines-of-a-jar)
- [Build the set](#build-the-set)
- [What is checked, and when](#what-is-checked-and-when)
- [Diagnostics](#diagnostics)

## What the producer publishes

| Module | Packages | Declares |
| --- | --- | --- |
| Producer | `pay`, `stock` | `Payments` over `Payment`, `Items` over `Item` |
| Consumer | `book`, `shop`, `app` | `Bookings`, `Orders`, their lookups, the set `Store` and the `Scenario` that runs it |

The producer's machines are ordinary machines. This is `Payments`, which names no booking and declares no peer:

```java
@GenerateGraphApi
@PaymentsGraph.Definition(
    initial = PENDING,
    terminals = {CAPTURED, DECLINED},
    state = PaymentRefs.Status.class,
    id = PaymentRefs.Id.class)
@PaymentsGraph.StatePolicy(state = PENDING, otherwise = "a payment is captured or declined")
public interface Payments extends Machine<Payment, PaymentStatus, PaymentTransition> {

    @PaymentsGraph.Transition(id = CAPTURE, from = PENDING, to = CAPTURED)
    Outcome<Payment, PaymentStatus, PaymentTransition> capture(Payment payment);

    @PaymentsGraph.Transition(id = DECLINE, from = PENDING, to = DECLINED)
    Outcome<Payment, PaymentStatus, PaymentTransition> decline(Payment payment);
}
```

`Items` has the same shape: an item is `OPEN` until `PACK` takes it to `PACKED`. For a consumer to use them, the
producer's artifact must meet three requirements:

- **The complete lifecycle.** Build the producer through generation, javac with the processor, and completion, as
  [building with the typed graph API](typed-annotations-build.md) describes. The completion step publishes the
  export index, `META-INF/graphmaquina/exports.json`, which describes the producer's machines and symbols.
- **The index inside the artifact.** The jar, or the directory of classes, that reaches the consumer's classpath
  carries the index beside the classes it describes. The consumer types the pair from what the index says of the
  peer, and checks it against the classes first. An assembly step that drops or overwrites `META-INF/graphmaquina`
  leaves the consumer with `SG602`, naming that jar or directory.
- **The same GraphMaquina version.** An index that another version of GraphMaquina wrote, an earlier one included,
  is refused with `SG602`: build the producer and the consumer with the same version, and rebuild the producer when
  the consumer moves to another.

[Multiple modules and published JARs](installation.md#multiple-modules-and-published-jars) lists the steps of a
build of several modules.

## Bind a machine of a jar as a sub-machine

The consumer's booking holds a payment of the producer in `DEPOSIT`: booking it starts the payment, and the terminal
state the payment reaches selects the booking's exit. The whole binding is declared on the consumer's `Bookings`,
which names the producer's `Payments` as its peer:

```java
@GenerateGraphApi
@GraphPeers(Payments.class)
@BookingsGraph.Definition(
    initial = REQUESTED,
    terminals = {CONFIRMED, CANCELLED},
    state = BookingRefs.Status.class,
    id = BookingRefs.Id.class)
@BookingsGraph.StatePolicy(state = REQUESTED, otherwise = "a booking is booked first")
@BookingsGraph.StatePolicy(state = DEPOSIT, otherwise = "the deposit payment decides")
@BookingsGraph.Transition(id = DEPOSIT_PAID, from = DEPOSIT, to = CONFIRMED)
@BookingsGraph.Transition(id = DEPOSIT_DECLINED, from = DEPOSIT, to = CANCELLED)
@BookingsPayments.Submachine(
    state = DEPOSIT,
    child = BookingRefs.Deposit.class,
    parentLookup = BookingLookupsRefs.BookingOf.class,
    exits = {
        @BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = DEPOSIT_PAID),
        @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = DEPOSIT_DECLINED)})
public interface Bookings extends Machine<Booking, BookingStatus, BookingTransition> {

    @BookingsGraph.Transition(id = BOOK, from = REQUESTED, to = DEPOSIT)
    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);
}
```

The generator writes the pair's vocabulary, `BookingsPayments`, from what the producer's export index says of
`Payments`, so `on` takes a `PaymentStatus` and `transition` a `BookingTransition`, as within one compilation.
[Compose machines](composition.md#declare-a-sub-machine) explains each element. Across modules, the way to the child
and the way back follow from the producer knowing no booking. `child` is a property of the consumer's entity that
returns an `Optional` of the child's entity:

```java
/** The payment of the deposit: the sub-machine that {@code DEPOSIT} holds. */
@GraphProperty
public Optional<Payment> deposit() {
    return Optional.of(deposit);
}
```

The way back from a payment to its booking cannot be `parent`, a property of the child's entity that returns the
parent's, since the producer's `Payment` cannot name a `Booking`. It is `parentLookup`, a lookup the consumer exports,
which finds the booking among those it was told about, where an application would ask its repository:

```java
@GraphLookup
public Optional<Booking> bookingOf(Payment payment) {
    return Optional.ofNullable(bookings.get(payment.id()));
}
```

The exits are checked against the terminal states the child declares in its jar: one exit per terminal state, each
naming an identifier with an edge from the bound state. An exit on `PENDING` in place of the one on `DECLINED` is
refused with both problems it causes:

```text
[SG801] Bookings.DEPOSIT: the terminal DECLINED of Payments has no exit
[SG801] Bookings.DEPOSIT: the exit on PENDING names no terminal of Payments
```

An exit whose identifier has no edge from the bound state, as when `DEPOSIT_DECLINED` leaves `REQUESTED` rather than
`DEPOSIT`, is refused with the very message it gets when both machines are compiled together:

```text
[SG802] Bookings.DEPOSIT: the exit on DECLINED names DEPOSIT_DECLINED, which has no edge from DEPOSIT
```

## Watch the machines of a jar

The consumer's order watches its items, items of the producer, while it is `PAID`, and ships once all of them are
`PACKED`. The watch is declared on the consumer's `Orders`, which names the producer's `Items` as its peer:

```java
@OrdersItems.Watch(
    state = PAID,
    peers = OrderRefs.Items.class,
    ownerLookup = OrderLookupsRefs.OrderOf.class,
    rules = @OrdersItems.When(allIn = ItemStatus.PACKED, transition = SHIP))
```

`peers` is the consumer's `Order.items()`, a `Collection<Item>`. The way back from an item to its order cannot be a
property of the producer's `Item`, which knows no order, so `ownerLookup` names a lookup the consumer exports, of
signature `Optional<Order> name(Item)`:

```java
@GraphLookup
public Optional<Order> orderOf(Item item) {
    return Optional.ofNullable(orders.get(item.id()));
}
```

An empty `Optional` means the item has no order, and no reaction follows for it. The lookup is static or instance,
on any class of the consumer, and returns only an `Optional`;
[find the owner with a lookup](coordination.md#find-the-owner-with-a-lookup) has the whole contract.

`owner` is enough when the peer's entity already has a property that returns the owner, as when the producer also
publishes the owner's entity, a domain both modules share. With `Item`, `Order` and `Items` in the producer's jar and
`Orders` in the consumer, `owner = ItemRefs.Order.class` names the item's `Optional<Order> order()`, as in
[watch other machines](coordination.md#watch-other-machines). A watch declares exactly one of `owner` and
`ownerLookup`; neither or both is `SG405`.

When the last item commits `PACKED`, the order is reevaluated after that commit, in a new transaction, as for any
watch: [triggering and observing watches](coordination.md#triggering-and-observing-watches) describes it.

## Build the set

The consumer's `@StateGraphs` set holds its machines and the producer's machines they bind and watch. A set that holds
a parent must hold its child (`SG806`), and a set that holds a watch must hold its peers (`SG510`):

```java
@StateGraphs
public interface Store extends Machines {

    Bookings bookings();

    Payments payments();

    Orders orders();

    Items items();
}
```

The generated builder takes the instance hosts of the consumer's machines in the order of the set's accessors: the
booking lookups of `Bookings`, then the order lookups of `Orders`. The scenario keeps both, to tell them which
booking and which order each payment and item belongs to:

```java
public static String run() {
    BookingLookups bookingLookups = new BookingLookups();
    OrderLookups orderLookups = new OrderLookups();
    try (Store store = StoreRuntime.builder(bookingLookups, orderLookups).build()) {
        return deposit(store, bookingLookups) + " / " + packing(store, orderLookups);
    }
}
```

```java
/** Books a booking, captures its deposit, and reports the states of both. */
private static String deposit(Store store, BookingLookups bookingLookups) {
    Payment deposit = new Payment("deposit-1");
    Booking booking = new Booking("booking-1", deposit);
    bookingLookups.register(booking);
    store.bookings().book(booking);
    store.payments().capture(deposit);
    return booking.status() + " " + deposit.status();
}

/** Pays an order of two items, packs them one by one, and reports the order's state after each. */
private static String packing(Store store, OrderLookups orderLookups) {
    Item first = new Item("item-1");
    Item last = new Item("item-2");
    Order order = new Order("order-1", List.of(first, last));
    orderLookups.register(order);
    store.orders().pay(order);
    store.items().pack(first);
    OrderStatus afterFirst = order.status();
    store.items().pack(last);
    return afterFirst + " " + order.status();
}
```

`run()` returns `CONFIRMED CAPTURED / PAID SHIPPED`: capturing the deposit exits the booking through `DEPOSIT_PAID`
to `CONFIRMED`; the order stays `PAID` while one item is still `OPEN`, and ships once the last one is packed. The
same holds when the producer reaches the consumer as a directory of classes that carries the index rather than as a
jar.

The set can also live in a third module. With the payments in `pay.jar` and the bookings in `book.jar`, compiled
against `pay.jar`, a third module's set `Desk` that holds `Bookings` and `Payments` builds and runs the deposit
scenario. Holding `Bookings` alone, it is refused as a set of one compilation is:

```text
[SG806] the sub-machine of Bookings.DEPOSIT is Payments, which is not in Desk
```

## What is checked, and when

The build checks the relation against the producer's artifact it compiles against:

| Step | Check | Code |
| --- | --- | --- |
| Generation, before anything is published | An index on the classpath was written by this version, and no two describe one machine | `SG602` |
| Generation, before anything is published | A peer of a dependency comes from an artifact whose index lists it as its classes describe it, over enums, and through supertypes, the consumer's classpath declares | `SG602` |
| Generation | A peer is a machine of this compilation or of a dependency's index, whose types the owner's package can name | `SG608` |
| Generation | No type of the owner's package obscures a package the pair vocabulary writes, such as the peer's | `SG603` |
| Processor | Each exit matches a terminal state of the child and has an edge from the bound state | `SG801`, `SG802` |
| Processor | A watch declares exactly one of `owner` and `ownerLookup` | `SG405` |
| Processor | A `parentLookup` or an `ownerLookup` has the signature its binding needs | `SG505` |
| Processor | The set holds each child and each watched peer | `SG806`, `SG510` |

At run time, the classpath can carry another build of the producer than the one the consumer compiled against. Each
companion checks its bindings again against the definition of the child it receives, when the set's generated
builder builds the graphs, before any call. The consumer above, run with a `Payments` rebuilt with a third terminal
state, `VOIDED`, fails as `StoreRuntime.builder(...)` builds the graphs, with an `InvalidGraphException` that carries
one problem:

```text
[SG801] Booking/BookingStatus.DEPOSIT: the terminal VOIDED of Payment/PaymentStatus has no exit
```

Run with the producer's artifact the consumer was compiled against, or rebuild the consumer against the new one and
give its binding an exit for the new terminal state: until it declares one, that build refuses the binding with
`SG801`, which names the terminal state that has no exit.

## Diagnostics

| Code | Across modules it means | Remedy |
| --- | --- | --- |
| [`SG602`](diagnostics.md#generation-and-typed-binding-diagnostics) | The producer's artifact carries no export index, one the generator cannot read, one another version wrote, or one that does not list the peer or describes it otherwise than its classes; the peer passes `Machine` an enum, or reaches it only through a supertype, that the consumer's classpath does not declare; or two artifacts on the classpath describe the same machine. The message names the jar or directory whenever the generator can tell it | Package the index the producer's build publishes with its classes, rebuild the producer through the complete lifecycle with the consumer's version, add the dependency that declares a missing enum or supertype to the consumer's classpath, or keep one artifact of the producer on the classpath. [Build troubleshooting](build-troubleshooting.md#troubleshooting) lists the messages and their remedies |
| [`SG608`](diagnostics.md#generation-and-typed-binding-diagnostics) | The peer is no machine of this compilation or of a dependency's export index, or the owner's package cannot name its types, as when its state enum is not public | Name a `@GenerateGraphApi` machine whose types the owner's package can reach |
| [`SG405`](diagnostics.md#graph-and-declaration-diagnostics) | A watch declares neither or both of `owner` and `ownerLookup` | Declare `ownerLookup` when the peer's entity cannot name the owner, `owner` when it can |

---

Next: [Commit, hooks, observers and persistence](commit-and-persistence.md). Back to the [documentation index](README.md).
