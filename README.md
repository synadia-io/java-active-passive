# java-active-passive
JNATS Active / Passive Connection Extension

[![jnats canary](https://github.com/synadia-io/java-active-passive/actions/workflows/jnats-canary.yml/badge.svg)](https://github.com/synadia-io/java-active-passive/actions/workflows/jnats-canary.yml)

### Active connection server pool selection

```
      ┌─────────────────────────────────────────────────────┐
      │ Did ApOptions have an active ApServerPool instance? │
      └─────────────────────────────────────────────────────┘
             │                          │
            YES                         NO
             │                          │
             ▼                          ▼
┌──────────────────────┐   ┌─────────────────────────────────────┐
│ Use that pool as the │   │ Did the user supply a server pool   │
│ server pool for the  │   │ in the regular connection Options?  │
│ active instance      │   └─────────────────────────────────────┘
└──────────────────────┘           │                     │
                                  YES                    NO
                                   │                     │
                                   ▼                     ▼
                ┌───────────────────────────┐  ┌────────────────────────────────┐
                │ Is that server pool an    │  │ Create an ApPassiveServerPool  │
                │ instance of ApServerPool? │  │ with a standard NatsServerPool │
                └───────────────────────────┘  │  as the delegate               │
                      │                │       └────────────────────────────────┘                       
                     YES               NO      
                      │                │
                      ▼                ▼
        ┌──────────────────────┐  ┌───────────────────────────────┐
        │ Use that pool as the │  │ Create an ApPassiveServerPool │
        │ server pool for the  │  │ with that pool as the         │
        │ active instance      │  │ delegate                      │
        └──────────────────────┘  └───────────────────────────────┘
```

### Passive connection server pool selection

```
      ┌─────────────────────────────────────────────────────┐
      │ Did ApOptions have a passive ApServerPool instance? │
      └─────────────────────────────────────────────────────┘
             │                          │
            YES                         NO
             │                          │
             ▼                          ▼
┌──────────────────────┐   ┌────────────────────────────────┐
│ Use that pool as the │   │ Use the active server pool as  │
│ server pool for the  │   │ resolved above for the passive │
│ passive instance     │   │ instance                       │
└──────────────────────┘   └────────────────────────────────┘
```
