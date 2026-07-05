# What Is a Rule Engine?

A rule engine runs business rules. It keeps that logic out of your application code so the people who understand the business — not just developers — can read and change it.

## Core Concepts

### Rules

Every rule has two parts: a condition and an action. If the condition matches, the action fires.

```
rule "Flag large transactions"
when
    Transaction( amount > 10000 )
then
    alert.flag( "Large transaction detected" );
end
```

### Working Memory

This is where your data lives at runtime. The engine watches it and fires rules whenever the data matches a condition.

### The Inference Engine

The engine compares your data against every rule, collects the ones that match, then decides which to run first. This matching step is what makes rule engines fast even with thousands of rules — the RETE algorithm avoids re-evaluating rules whose inputs haven't changed.

## Why Use One?

Three good reasons:

- **Readable logic** — domain experts can read rules directly; no translation needed
- **Audit trail** — you can trace exactly which rules fired and why
- **Easy updates** — change a rule without touching application code or doing a full redeploy

## When It's Not Worth It

Don't reach for a rule engine to replace five `if/else` statements. The operational overhead isn't worth it for simple cases.

They earn their place when you have dozens or hundreds of overlapping rules that non-developers need to maintain. That's the tipping point.

## Further Reading

- Drools: https://drools.org
- RETE: Forgy (1982), *Artificial Intelligence* 19(1)
