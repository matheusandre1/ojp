# ADR: Refactor StatementServiceImpl using Action Pattern

## Status
Proposed

## Date
2026-01-06

## Context

The `StatementServiceImpl` class in the `ojp-server` module has grown to 2,528 lines of code and has become a God class that violates the Single Responsibility Principle. The class handles 21 different gRPC service endpoints, manages 9 different maps and collections, and contains 14+ private helper methods.

### Current Problems

1. **Maintainability**: Changes to one operation risk breaking others due to shared state and complex interactions
2. **Testability**: The class is difficult to unit test; most tests are integration tests at the service level
3. **Readability**: Developers struggle to navigate the large file and understand specific operations
4. **Collaboration**: Multiple developers working on different features cause frequent merge conflicts
5. **Extensibility**: Adding new features requires understanding the entire 2,528-line class

### Key Metrics

- **Lines of code**: 2,528 (vs. recommended max 500-1000 for maintainability)
- **Public methods**: 21 (gRPC endpoints)
- **Private methods**: 14+ (some over 200 lines)
- **Shared state**: 9 maps/collections + 3 service dependencies
- **Cyclomatic complexity**: Very high in methods like `connect()` (143 lines)

## Decision

We will refactor `StatementServiceImpl` using the **Action Pattern** where:

1. Each public method delegates to a dedicated `Action` class
2. An `ActionContext` class holds all shared state and dependencies
3. Action classes are organized in a package hierarchy by functionality
4. The refactored `StatementServiceImpl` becomes a thin orchestrator (~400 lines)

### Action Pattern Structure

```java
// Before
public void connect(ConnectionDetails details, StreamObserver<SessionInfo> observer) {
    // 143 lines of logic
}

// After
public void connect(ConnectionDetails details, StreamObserver<SessionInfo> observer) {
    new ConnectAction(actionContext).execute(details, observer);
}
```

### Package Structure

```
org.openjproxy.grpc.server.action/
├── Action.java (interface)
├── ActionContext.java (shared state holder)
├── connection/ (ConnectAction + helpers)
├── statement/ (ExecuteUpdate/QueryAction + internals)
├── transaction/ (Start/Commit/RollbackAction)
├── xa/ (9 XA transaction actions)
├── lob/ (CreateLob/ReadLobAction + helpers)
├── session/ (TerminateSession, SessionConnection)
├── resource/ (CallResourceAction + helpers)
└── util/ (ProcessClusterHealth, HandleResultSet)
```

## Alternatives Considered

### Alternative 1: Strategy Pattern
**Description**: Define a strategy interface for each type of operation (query, transaction, XA, etc.) with multiple implementations.

**Pros**:
- Allows runtime strategy selection
- Good for algorithm variation

**Cons**:
- Too abstract for this use case
- Doesn't reduce line count significantly
- Adds unnecessary complexity
- Still need to manage shared state

**Rejected because**: Doesn't address the core problem of too many responsibilities in one class.

### Alternative 2: Template Method Pattern
**Description**: Create an abstract base class with template methods for common operations, extend for specific operations.

**Pros**:
- Enforces consistent structure
- Good for shared behavior

**Cons**:
- Still requires large base class with shared state
- Inheritance hierarchy can become complex
- Doesn't improve testability much
- Hard to navigate inheritance chains

**Rejected because**: Inheritance-based approach doesn't align well with composition-friendly architecture.

### Alternative 3: Command Pattern with Factory
**Description**: Each operation as a Command object, created via CommandFactory, with command queue for execution.

**Pros**:
- Very flexible
- Supports undo/redo (not needed here)
- Allows command queuing (not needed here)

**Cons**:
- Adds factory layer complexity
- Command queue not needed for gRPC
- Harder to find code (indirect through factory)
- Over-engineered for this use case

**Rejected because**: Action pattern provides the benefits of Command without unnecessary factory complexity.

### Alternative 4: Extract to Separate Service Classes
**Description**: Break StatementServiceImpl into multiple service classes (ConnectionService, TransactionService, XAService, etc.).

**Pros**:
- Clear service boundaries
- Good separation of concerns

**Cons**:
- How to share state between services?
- gRPC service implementation expects single class
- Would need complex service registry
- Doesn't align with gRPC code generation

**Rejected because**: Doesn't fit well with gRPC's service definition structure and would complicate dependency injection.

### Alternative 5: Do Nothing (Keep Status Quo)
**Description**: Leave the class as-is and continue adding features.

**Pros**:
- No refactoring cost
- No risk of breaking changes

**Cons**:
- Technical debt continues to grow
- Maintenance becomes increasingly difficult
- New developers struggle to understand code
- Testing becomes harder over time
- Risk of bugs increases with complexity

**Rejected because**: Technical debt has reached critical mass; refactoring is necessary for long-term maintainability.

## Decision Rationale

The **Action Pattern** was chosen because it:

1. **Directly addresses the problem**: Each action has one responsibility
2. **Maintains existing API**: Public methods unchanged, only implementation refactored
3. **Improves testability**: Each action can be unit tested independently with mock ActionContext
4. **Simple to understand**: Direct mapping from method name to action class name
5. **Easy to navigate**: Developers can find code by action class name
6. **Supports composition**: Actions can call other actions when needed
7. **Gradual migration**: Can be implemented incrementally over 9 weeks
8. **Low risk**: Each action can be tested independently before removing old code
9. **Aligns with gRPC**: One service implementation class delegates to focused actions
10. **No framework lock-in**: Pure Java pattern, no external dependencies

## Consequences

### Positive

1. **Reduced complexity**: Main class reduced from 2,528 to ~400 lines (84% reduction)
2. **Improved testability**: 35+ focused unit test suites instead of one large suite
3. **Better maintainability**: Changes localized to specific action classes
4. **Easier onboarding**: New developers can understand one action at a time
5. **Parallel development**: Multiple developers can work on different actions without conflicts
6. **Clear navigation**: IDE "Go to Definition" takes you directly to action implementation
7. **Better code review**: Smaller, focused changes easier to review
8. **Extensibility**: New features added as new actions without touching existing code

### Negative

1. **More files**: 35+ new action classes (but each is small and focused)
2. **Learning curve**: Team needs to understand Action pattern and ActionContext
3. **Refactoring effort**: ~200-300 hours over 9 weeks
4. **Risk of bugs during migration**: Mitigated by incremental approach and comprehensive testing
5. **Temporary duplication**: During migration, old and new code coexist briefly

### Neutral

1. **Performance**: No expected impact (actions are lightweight, context is reused)
2. **Memory**: Minimal increase (action instances are short-lived, context is singleton)
3. **Build time**: Slight increase due to more files (negligible)

## Implementation Plan

### Phase 1: Infrastructure (Week 1)
- Create ActionContext with all shared state
- Create Action interfaces (Action, StreamingAction, ValueAction, InitAction)
- Set up package structure
- Add to StatementServiceImpl (no functional changes yet)

### Phase 2-6: Incremental Action Extraction (Week 2-9)
- Extract actions incrementally, starting with simplest
- Each extraction follows: Create action → Test → Update StatementServiceImpl → Remove old code
- Run full test suite after each action
- Code review after each phase

### Success Criteria
- ✅ All existing tests pass
- ✅ No performance regression (< 5% acceptable)
- ✅ Test coverage maintained or improved
- ✅ StatementServiceImpl < 500 lines
- ✅ Each action class < 200 lines
- ✅ No Sonar critical issues

## Monitoring

We will monitor:
1. **Code metrics**: Line count per class, cyclomatic complexity
2. **Test metrics**: Coverage percentage, test execution time
3. **Performance metrics**: Request latency, throughput
4. **Quality metrics**: Sonar issues, code smells

## Compliance

This refactoring:
- ✅ Maintains backward compatibility (same public API)
- ✅ Follows existing code style and conventions
- ✅ Complies with Java 21 requirements
- ✅ Works with existing gRPC infrastructure
- ✅ Compatible with existing dependency injection setup

## Related Documents

- [Main Refactoring Design](./STATEMENTSERVICE_REFACTORING.md)
- [Detailed Design](./STATEMENTSERVICE_REFACTORING_DETAILED.md)
- [Implementation Checklist](./STATEMENTSERVICE_REFACTORING_CHECKLIST.md)
- [Architecture Diagrams](./STATEMENTSERVICE_REFACTORING_DIAGRAMS.md)

## Review & Approval

This ADR requires approval from:
- [ ] Tech Lead
- [ ] Senior Architect
- [ ] Development Team (consensus)

## References

- Fowler, M. "Refactoring: Improving the Design of Existing Code"
- Martin, R. "Clean Code: A Handbook of Agile Software Craftsmanship"
- Action Pattern: https://en.wikipedia.org/wiki/Command_pattern (variant)
- God Class Anti-pattern: https://sourcemaking.com/antipatterns/the-blob

## Notes

- This is a **design-only** ADR; implementation follows in separate PRs
- Migration strategy is incremental to minimize risk
- Each phase can be merged independently
- Rollback possible at any phase boundary
- Original code preserved until each action is fully tested

---

**ADR Number**: TBD  
**Created**: 2026-01-06  
**Last Updated**: 2026-01-06  
**Status**: Proposed → Pending Approval  
**Decision Makers**: Tech Lead, Senior Architect, Development Team
