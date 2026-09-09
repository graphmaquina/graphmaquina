# Migrating from v0.2 to the typed graph API (v0.3)

In v0.2 a machine named its states, methods and properties as strings. In v0.3 it references **generated
annotations and generated symbol classes**, so javac checks every reference and the IDE resolves it. Only two
kinds of text survive inside a declaration: the `otherwise` reason of a row and the `reason` of a `Forbid`,
which are messages, not names.

The old vocabulary (`@StateGraph`, `@Row`, `@Action`, `@Move`, `@Cell`, `@Forbid`, `@Watch`, `@When` and the
containers) still exists, is `@Deprecated(since = "0.3")`, and is accepted by nothing: the processor reports
**SG609** at every declaration that still uses it, with the typed declaration to write instead. There is no
silent mixing of the two vocabularies. `@StateGraphs` and `Machines` are unchanged.

## What the generator produces

For a machine `Orders` in package `p`, the generator writes into that same package:

| Generated type | What it is |
| --- | --- |
| `p.OrdersGraph` | the machine's vocabulary: `Definition`, `Row`/`Rows`, `Action`, `Move`, `Cell`/`Cells`, `Forbid`/`Forbids` |
| `p.OrdersItems` | the watch vocabulary of the pair `Orders` × `Items`: `Watch`/`Watches` and `When` |
| `p.OrderRefs` | one symbol class per exported member of the entity `Order` |
| `p.OrderActionsRefs` | one symbol class per exported member of the host `OrderActions` |

The enum-valued elements of that vocabulary are typed with the machine's own state enum, and the peer's
elements with the peer's enum, so a state of another machine does not compile.

## Step by step

### 1. Move the build to 0.3

Generation is mandatory and runs before javac. Set up the Gradle plugin or the six Maven goals first, as
`typed-annotations-build.md` describes, and raise every StateGraph dependency to `1.0.0`. Until the
build is right, everything else fails with SG602 (no manifest) rather than with a migration error.

### 2. Mark the members the graphs reference

Nothing is exported implicitly. Every member a declaration will point at carries a marker:

| Marker | On | Exports |
| --- | --- | --- |
| `@GraphAction` | method | an action, referenced as `<Host>Refs.<Method>.class` |
| `@GraphGuard` | method | a guard |
| `@GraphHook` | method | a post-commit hook |
| `@GraphCondition` | method | a watch condition |
| `@GraphProperty` | field or accessor | a property of an entity |
| `@GraphBindings` | type | a concrete host whose **inherited** marked members are exported as its own |

```java
public class OrderActions {

    @GraphAction
    public ActionOutcome charge(TransitionContext<Order, OrderStatus> context) { … }

    @GraphGuard
    public static boolean retryBudgetLeft(Order order) { … }

    @GraphHook
    public void notifyCarrier(TransitionContext<Order, OrderStatus> context) { … }
}
```

A member marked with a contract its signature does not satisfy is refused with **SG604** at the marker, whether
or not anything references it. A symbol used in a slot of the wrong category — a guard where an action belongs
— does not compile at all: the symbol classes implement `ActionSymbol`, `GuardSymbol`, `HookSymbol`,
`ConditionSymbol` and `PropertySymbol`, and each element of the vocabulary is typed with the one it accepts.

#### Properties: field or getter

`@GraphProperty` goes on the field when the entity has a conventional accessor for it — Lombok's `@Getter`
counts, because the generator reads the accessors Lombok has already generated — and on the accessor itself
otherwise:

```java
@Getter @Setter
public class Order {

    @GraphProperty
    private String id;                    // Lombok writes getId(); the field carries the marker

    @GraphProperty
    private OrderStatus status;

    @GraphProperty
    public List<Item> items() {           // a hand-written reader: the marker goes on the method
        return items;
    }
}
```

The **logical name** of a property is the field's name, or the getter's name with a `get`/`is` prefix removed
and the JavaBeans decapitalization applied — `getURL()` exports `URL`, `getStatus()` exports `status`,
`items()` exports `items`. That logical name is what the generated symbol is named after.

#### Inheritance: `@GraphBindings`

Markers on inherited members belong to the type that declares them. When the graph must bind the **concrete**
host — a Spring bean whose actions are declared on an abstract base or an interface — mark that concrete class
with `@GraphBindings`, and its inherited marked members are exported through its own `<Host>Refs`.

### 3. Rewrite the machine

`graphmaquina-example`'s order machine, before and after. This is the whole shape of the migration.

**v0.2**

```java
@StateGraph(initial = "NEW", terminals = {"SHIPPED", "CANCELLED"}, allowsCycle = "PAYMENT_DECLINED")
@Row(state = "NEW",              otherwise = "cannot ship without payment")
@Row(state = "PAID",             otherwise = "money was charged: requires a refund")
@Row(state = "PAYMENT_DECLINED", otherwise = "go back to NEW to retry the charge")
@Row(state = "SHIPPED",          otherwise = "a shipment cannot be undone")
@Row(state = "CANCELLED",        otherwise = "cancelled is final")
@Cell(from = "NEW", to = "PAYMENT_DECLINED")
@Watch(state = "PAID", peer = Items.class, peers = "items", owner = "order",
       rules = @When(allIn = "PACKED", goTo = "SHIPPED"))
public interface Orders extends Machine<Order, OrderStatus> {

    @Action(from = "NEW", to = "PAID", by = OrderActions.class, action = "charge",
            guards = "retryBudgetLeft", onFailure = "PAYMENT_DECLINED",
            failsOn = IOException.class, timeout = "PT5S")
    Outcome<Order, OrderStatus> pay(Order order);

    @Move(from = "PAYMENT_DECLINED", to = "NEW", by = OrderActions.class, guards = "retryBudgetLeft")
    Outcome<Order, OrderStatus> retry(Order order);

    @Action(from = "PAID", to = "SHIPPED", by = OrderActions.class, guards = "allItemsPacked",
            afterCommit = "notifyCarrier")
    Outcome<Order, OrderStatus> ship(Order order);

    @Action(from = {"NEW", "PAYMENT_DECLINED"}, to = "CANCELLED", by = OrderActions.class)
    Outcome<Order, OrderStatus> cancel(Order order);
}
```

**v0.3**

```java
import static io.github.graphmaquina.example.order.OrderStatus.CANCELLED;
import static io.github.graphmaquina.example.order.OrderStatus.NEW;
import static io.github.graphmaquina.example.order.OrderStatus.PAID;
import static io.github.graphmaquina.example.order.OrderStatus.PAYMENT_DECLINED;
import static io.github.graphmaquina.example.order.OrderStatus.SHIPPED;

@GenerateGraphApi
@GraphPeers(Items.class)
@OrdersGraph.Definition(initial = NEW, terminals = {SHIPPED, CANCELLED}, allowsCycle = PAYMENT_DECLINED,
        state = OrderRefs.Status.class, id = OrderRefs.Id.class)
@OrdersGraph.Row(state = NEW,              otherwise = "cannot ship without payment")
@OrdersGraph.Row(state = PAID,             otherwise = "money was charged: requires a refund")
@OrdersGraph.Row(state = PAYMENT_DECLINED, otherwise = "go back to NEW to retry the charge")
@OrdersGraph.Row(state = SHIPPED,          otherwise = "a shipment cannot be undone")
@OrdersGraph.Row(state = CANCELLED,        otherwise = "cancelled is final")
@OrdersGraph.Cell(from = NEW, to = PAYMENT_DECLINED)
@OrdersItems.Watch(state = PAID, peers = OrderRefs.Items.class, owner = ItemRefs.Order.class,
        rules = @OrdersItems.When(allIn = ItemStatus.PACKED, goTo = SHIPPED))
public interface Orders extends Machine<Order, OrderStatus> {

    @OrdersGraph.Action(from = NEW, to = PAID,
        action = OrderActionsRefs.Charge.class,
        guards = OrderActionsRefs.RetryBudgetLeft.class,
        onFailure = PAYMENT_DECLINED, failsOn = IOException.class,
        timeout = @Timeout(seconds = 5))
    Outcome<Order, OrderStatus> pay(Order order);

    @OrdersGraph.Move(from = PAYMENT_DECLINED, to = NEW, guards = OrderActionsRefs.RetryBudgetLeft.class)
    Outcome<Order, OrderStatus> retry(Order order);

    @OrdersGraph.Action(from = PAID, to = SHIPPED, action = OrderActionsRefs.Ship.class,
            guards = OrderActionsRefs.AllItemsPacked.class, afterCommit = OrderActionsRefs.NotifyCarrier.class)
    Outcome<Order, OrderStatus> ship(Order order);

    @OrdersGraph.Action(from = {NEW, PAYMENT_DECLINED}, to = CANCELLED, action = OrderActionsRefs.Cancel.class)
    Outcome<Order, OrderStatus> cancel(Order order);
}
```

What changed, element by element:

| v0.2 | v0.3 |
| --- | --- |
| `@StateGraph(initial = "NEW", …)` | `@GenerateGraphApi` on the type, and `@<Machine>Graph.Definition(initial = NEW, …)` |
| nothing | `state = <Entity>Refs.<Property>.class` and `id = <Entity>Refs.<Property>.class` are now explicit and required |
| `@Row(state = "PAID", …)` | `@<Machine>Graph.Row(state = PAID, …)` |
| `@Action(by = Host.class, action = "charge")` | `@<Machine>Graph.Action(action = HostRefs.Charge.class)` — `by` is gone: the symbol carries its host |
| `guards = "retryBudgetLeft"` | `guards = HostRefs.RetryBudgetLeft.class` |
| `afterCommit = "notifyCarrier"` | `afterCommit = HostRefs.NotifyCarrier.class` |
| `timeout = "PT5S"` | `timeout = @Timeout(seconds = 5)` |
| `onFailure = "PAYMENT_DECLINED"` | `onFailure = PAYMENT_DECLINED` |
| `@Cell` / `@Forbid` | `@<Machine>Graph.Cell` / `.Forbid`, same shape, typed |
| `@Watch(peer = Items.class, peers = "items", owner = "order")` | `@GraphPeers(Items.class)` on the type, and `@<Owner><Peer>.Watch(peers = <Entity>Refs.Items.class, owner = <PeerEntity>Refs.Order.class)` |
| `@When(allIn = "PACKED", goTo = "SHIPPED")` | `@<Owner><Peer>.When(allIn = ItemStatus.PACKED, goTo = SHIPPED)` |

### 4. Static-import the enum constants

An annotation element typed with an enum takes the constant, not a string. Static-importing the constants keeps
the declarations as readable as the string form was:

```java
import static io.github.graphmaquina.example.order.OrderStatus.NEW;
```

A peer's states are the peer enum's constants, so a rule spells `ItemStatus.PACKED` (or static-imports it too).
Mixing the two enums does not compile — that is the point.

### 5. Optional elements are arrays with at most one value

`onFailure` and `timeout` (of `Action` and `Cell`), `afterCommit` (of `Action`, `Move` and `Cell`),
`condition` (of `When`) and `action` (of `Cell`) are declared as arrays so that "absent" is expressible. A
`Move` runs no action, so it declares only `guards` and `afterCommit`. Write **zero or one** value:

- `onFailure = PAYMENT_DECLINED` — one failure state; omit it entirely when a failed action does not move;
- `timeout = @Timeout(seconds = 5)` — `@Timeout` replaces the ISO-8601 string and keeps nanosecond
  precision (`@Timeout(seconds = 0, nanos = 500_000)`); a timeout is only for an asynchronous action;
- `afterCommit = HostRefs.NotifyCarrier.class` — one hook;
- a `Cell` with no `action` is a move; a `Cell` with one `action` is an action.

More than one value in any of them is **SG607**.

### 6. Watches

Declare the peers on the machine and use the pair's vocabulary:

```java
@GraphPeers({Bills.class, Refunds.class})
```

`@GraphPeers` is what makes the generator emit `<Owner><Peer>` for each pair. A peer declared twice, a peer
that is not a `@GenerateGraphApi` machine, or a peer nothing watches is **SG608**, and so is a peer this very
compilation does not generate: a watched pair is typed with both machines' states, so a peer that lives in
another module or in a dependency jar cannot be watched yet. In a `Watch`, `peers` is the owner entity's
collection property and `owner` is the peer entity's back-reference property, both as
`<Entity>Refs.<Property>.class`. A rule is `condition` (a `@GraphCondition` method) **or** exactly one of
`allIn` / `anyIn` / `noneIn` over the peer's enum, plus the `goTo` state of the owner. Rule order is kept: the
first rule that holds wins.

### 7. How generated symbols are named

Given a host `Order` in package `p`, its symbols live in `p.OrderRefs`, one nested final class per exported
member, named after the member:

- a name starting with a lower-case letter is upper-cased: `charge` → `Charge`, `items` → `Items`;
- any other first character is prefixed with `_`: `URL` → `_URL`, `_id` → `__id`;
- the one name that would collide with the container itself is prefixed with `_sg_`;
- a nested host joins its enclosing names with `_`: `Outer.Inner` exports through `Outer_InnerRefs`.

The mapping is injective, so two members never share a symbol. Overloads share one logical member only when
they are the same member; a marked overload and an unmarked one are different members, and the generated call
runs exactly the marked one.

### 8. Build and read the diagnostics

| Code | Situation |
| --- | --- |
| SG601 | Bootstrap: a required type is unresolved, ambiguous or contradictory |
| SG602 | Manifest absent, incompatible or stale |
| SG603 | Output or export collision |
| SG604 | Invalid, contradictory or undeclared export or role |
| SG605 | A symbol with no valid original member, or the wrong host |
| SG606 | Vocabulary of another machine or of another pair |
| SG607 | Invalid cardinality of an optional element |
| SG608 | A peer declaration duplicated, invalid or unused, or a watched peer that this compilation does not generate |
| SG609 | A legacy String-based declaration |

The v0.2 codes are unchanged where the rule is unchanged: operation signatures still report SG505, incompatible
accessors SG503, and the structural checks — closed rows, mandatory reasons, the STAY diagonal, duplicates,
terminals, reachability, cycles, `failsOn` restrictions, asynchronous timeouts, failure paths, watch
destinations and the set checks — keep the codes they had. An enum of the wrong type or a symbol of the wrong
category is refused by javac itself and is not wrapped in an SG code.

## What does not change

- The facades: the operations, `definition`, `allowedFrom`, `reevaluate` and `async`; `<Interface>_` and
  `<Set>_`, their builders and the direct calls they emit.
- The labels of the diagrams: an action's label is still the operation's name, or the chosen method in an
  internal cell; guards, hooks and conditions keep their original method names, never the symbol class names.
- The runtime: symbols are compile-time references. They create no instance, no bean and no reflective call,
  and neither the generator nor the processor reaches the application's runtime classpath.
- `@StateGraphs` and the set entry points.
