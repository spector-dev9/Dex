# Connection endpoints required by the app

Source: live `GET /doc` from `opencode 1.19.3` on 127.0.0.1.

| Method | Path | Operation | Request | Success response |
|---|---|---|---|---|
| GET | `/global/health` | `global.health` | `-` | `object{healthy,version}` |
| GET | `/event` | `event.subscribe` | `-` | `text/event-stream<Event>` |
| GET | `/config/providers` | `config.providers` | `-` | `object{providers,default}` |
| GET | `/session` | `session.list` | `-` | `array<Session>` |
| POST | `/session` | `session.create` | `object{parentID,title,agent,model,metadata,permission,workspaceID}` | `Session` |
| GET | `/session/status` | `session.status` | `-` | `map<string,SessionStatus>` |
| GET | `/session/{sessionID}` | `session.get` | `-` | `Session` |
| GET | `/session/{sessionID}/message` | `session.messages` | `-` | `array<object{info,parts}>` |
| POST | `/session/{sessionID}/prompt_async` | `session.prompt_async` | `object{messageID,model,agent,noReply,tools,format,system,variant,parts}` | `Prompt accepted` |
| POST | `/session/{sessionID}/abort` | `session.abort` | `-` | `boolean` |
| POST | `/session/{sessionID}/permissions/{permissionID}` | `permission.respond` | `object{response}` | `boolean` |
| POST | `/session/{sessionID}/command` | `session.command` | `object{messageID,agent,model,arguments,command,variant,parts}` | `object{info,parts}` |
