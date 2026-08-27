MCP client
   │ OIDC/JWT
   ▼
AgentCore Gateway
   ├─ Authenticate and authorize tool invocation
   ├─ Route call
   └─ Emit/associate safe technical context
             │
             ▼
Tool target
   └─ Publish minimized business outcome
             │
             ▼
Demand-signal processor
   ├─ Resolve internal subject/account
   ├─ Determine jurisdiction and relationship
   ├─ Call Privacy Preference / Policy API
   ├─ Apply minimization, taxonomy, dedupe
   └─ Make an export decision
             │
             ├─ Eligible → NovaSilva → Eloqua
             └─ Ineligible → suppress marketing event
                              retain only permitted service/security record
