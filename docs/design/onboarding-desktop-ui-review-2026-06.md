# Letta Onboarding And Desktop UI Review

Date: 2026-06-21  
Source file: [Penpot prototype](https://prototype.oculair.ca/#/workspace?team-id=6825745b-9463-814b-8003-f119039d044d&file-id=46e68d89-bd4b-8008-8007-fa0da54a88af&page-id=9ad9fa5c-d4f1-80b2-8008-321c1ee404a4)

## Scope

This review covers the desktop agent experience, the current onboarding sequence, and the main page surfaces that currently read as sterile or old:

- Desktop conversation and first-run agent screens
- Onboarding screens 1-8
- Memory, tools, schedules, chat, and settings pages
- Agent rail behavior and desktop navigation hierarchy

The feedback is grounded in the current Penpot boards and contemporary product examples from Mobbin.

## Executive Take

The current direction is structurally sound, but the product does not yet feel as modern or agent-native as the concept. The main issue is not missing functionality. It is that the screens repeatedly use the same visual recipe: dark canvas, teal accent, centered copy, rounded cards, list rows, and generic icons.

Letta is about private, controllable agents. The UI should constantly show the agent becoming useful: what it knows, what it can do, what it will do next, and what changed because of the user's choices. Right now, many screens show setup fields and settings lists more than outcomes.

The next iteration should make onboarding feel like building a living agent profile, and make each product page feel specific to its job rather than another settings surface.

## What Is Working

### Agent-centric desktop framing

The desktop direction with agents as the primary rail concept is strong. The rail should remain an agent switcher, not a generic app nav. The user's note that the rail represents agents is correct: it can be visually quieter by default and reveal more information on hover.

Recommended behavior:

- Default state: compact avatars or initials with subtle status rings.
- Hover state: full-size agent row with name, status, and possibly last active context.
- Tooltip: agent name, model/runtime, and current status.
- Active state: visible enough to orient the user, but not so bright that the rail competes with the conversation.

Relevant references:

- [Linear](https://mobbin.com/screens/eebedb56-835f-42a5-bfc0-df67f2006bc5) for quiet workspace chrome and strong content focus.
- [Linear](https://mobbin.com/screens/c288bb0f-7c2f-41fc-907c-7d3f69f1e848) for dense navigation that does not overdecorate every item.
- [Langdock](https://mobbin.com/screens/b66870ae-8d47-493d-b1b6-e6c01173ad96) for AI workspace structure with chat as the core surface.

### First-run chat direction

`Desktop - New agent first-run` is the strongest current board. The "Hi, I'm Nova" moment, setup checklist, starter prompts, and composer all point toward immediate activation. This should become the success destination for onboarding instead of a generic completion screen.

Recommended evolution:

- Keep the central greeting.
- Add a small agent status strip near the composer: runtime, memory state, enabled tools, and privacy mode.
- Keep the setup checklist, but make it feel like completing agent readiness rather than generic onboarding.
- Use starter prompts that reflect the user's onboarding inputs.

Relevant references:

- [Claude](https://mobbin.com/screens/6aaeec9e-bda5-49bd-91ad-a83106741fd7) for clean chat focus with restrained surrounding UI.
- [Claude](https://mobbin.com/screens/587db0a6-6f7d-4a6a-b1ac-80ea594f7c7a) for minimal chat scaffolding.
- [ChatGPT](https://mobbin.com/screens/9ecd64ee-6c45-4183-945f-58aa27a29585) for first-run chat prompt density and simple entry.
- [Langdock](https://mobbin.com/screens/b05495f9-e6dd-49f1-b87c-e5113e77ab44) for exposing AI workspace structure without overwhelming the chat.

## Where The Current Design Feels Sterile Or Old

### The abstract orb is doing too much work

The onboarding welcome, create-agent, and success screens repeat a large teal orb. It creates a brand signal, but it does not explain the product, preview the agent, or reward progress. After one screen, it starts to feel like placeholder art.

Replace the orb with a live agent preview:

- Agent avatar or identity card
- Local/server privacy state
- Memory count
- Enabled tools
- A short sample exchange
- A readiness checklist that fills in as the user progresses

Relevant references:

- [Chatbase onboarding flow](https://mobbin.com/flows/e476c604-a53d-4fe3-a171-b6321a436049) keeps onboarding tied to a concrete chatbot being configured.
- [Sana AI creating an agent flow](https://mobbin.com/flows/fc62b881-df54-4a27-8155-a6b18503cbe3) keeps the agent creation object visible.
- [Base44 creating a superagent flow](https://mobbin.com/flows/f49fea5a-49ef-4b9d-9286-9952494aee53) shows a creation flow that leads quickly into interaction.

### Too many pages use the same settings-list pattern

Memory, tools, settings, and schedules all lean on stacked rows, cards, and toggles. This is functional, but it makes pages feel interchangeable.

Each page should get a page-specific layout:

- Memory should feel like a knowledge base plus inspector.
- Tools should feel like a catalog plus permissions manager.
- Schedules should feel like a task builder.
- Settings should stay compact and utilitarian.

Relevant references:

- [Mixpanel](https://mobbin.com/screens/9db2d964-8fb0-4d0e-a660-bb3a9893e328) for dense inspector-style layouts.
- [Mixpanel](https://mobbin.com/screens/c84d8d04-4a1f-4cc2-8ea6-8c353a8238a9) for analysis pages with clear page-specific structure.
- [Motion](https://mobbin.com/screens/54ef392d-6ac8-4ec4-bc02-20174c08da26) for schedule/task planning surfaces.
- [Calendly](https://mobbin.com/screens/010463c7-1479-4b9b-bfaf-00df8e500795) for time-based configuration with clearer intent than generic forms.

### The visual system is too one-note

Black plus teal is doing nearly all the visual work. This makes the app recognizable, but it flattens hierarchy across pages.

Recommended adjustment:

- Use primary for the main action.
- Use tertiary for active, running, live, or capability-enabled states.
- Use secondary for supporting selections and filters.
- Keep surfaces quieter, with contrast coming from content hierarchy instead of accent borders.
- Avoid making every selected state a teal glow or teal stroke.

This aligns with the repo's Material role policy and the existing direction to introduce tertiary color more consistently.

## Onboarding Review And Proposal

### Current onboarding structure

The current onboarding boards cover:

1. Welcome
2. Choose local or server path
3. Connect server
4. Create agent
5. Seed profile
6. Choose personality
7. Enable capabilities
8. Setting up and success

This is a good flow. The issue is that it feels like forms and options rather than agent creation.

### Proposed framing

Replace the current first headline:

> Meet Letta  
> Your agents, on your terms.

With a more outcome-driven opener:

> Build your first private agent  
> Choose local-only or connect your Letta server. Then seed memory, tools, and personality before the first chat.

This is more concrete because it tells the user what they will finish with.

### Step 1: Welcome

Current issue:

- The welcome screen is visually clean but generic.
- The orb does not communicate local agents, server control, memory, or tools.

Recommended design:

- Left side: headline, short value prop, primary CTA.
- Right side: live preview card of the future agent.
- Preview state starts empty, then fills during the flow.

Concrete preview content:

- Agent: "Nova"
- Runtime: "Local"
- Memory: "0 entries"
- Tools: "None yet"
- Status: "Ready to configure"

Relevant references:

- [Notion](https://mobbin.com/screens/92c06d8a-8eb3-4aa9-8b6a-20abdbade8cb) for onboarding that uses a calm product surface rather than heavy decoration.
- [Google Workspace](https://mobbin.com/screens/05a829a2-525b-4a6e-911a-f4d5e2e5b9ce) for clear account/setup entry points.
- [Chatbase](https://mobbin.com/screens/a72f46fc-e9bf-4f8a-b7dd-bb34b8db4ea2) for making the chatbot setup object explicit.

### Step 2: Choose path

Current issue:

- "Create a new agent" and "Connect to a server" are clear, but they do not fully explain the tradeoff.
- The selected card is visually obvious, but the outcome difference is not.

Recommended copy:

Local agent:

> Private by default. Runs on this device. Good for personal memory and offline-first work.

Server agent:

> Connect your Letta deployment. Sync existing agents, tools, and channels.

Add comparison metadata:

- Storage: On device / Server-backed
- Setup time: Immediate / Requires URL and token
- Best for: Personal agent / Existing deployment

Relevant references:

- [n8n joining a workspace flow](https://mobbin.com/flows/243292b2-c4f5-45aa-9c64-13963a516cf0) for choosing an account/workspace path.
- [Outseta onboarding flow](https://mobbin.com/flows/bd71adf0-7324-4769-b552-42b2cd5125e0) for setup path decisions.

### Step 3: Connect server

Current issue:

- The screen is mostly URL/token input and a status message.
- It could better explain what connection unlocks.

Recommended design:

- Keep URL and token fields.
- Add a compact "What will sync" panel:
  - Agents
  - Tools
  - Memory blocks
  - Channels
- Show connection health as a real status object, not just a line of text.

Relevant references:

- [Gorgias](https://mobbin.com/screens/6018dd21-9c3a-4680-aa69-e46176d821db) for operational setup surfaces that remain clear and work-focused.
- [Klaviyo](https://mobbin.com/screens/e4d1f211-cd52-42a3-b835-556bf0508064) for structured setup flows with business/product context.

### Step 4: Create agent

Current issue:

- The name field and starter model are correct, but the screen has too much empty space and too little feedback.

Recommended design:

- Keep name and starter model.
- Add a persistent preview card that updates the agent name live.
- Show model/runtime as part of identity, not just a settings row.

Concrete example:

> Agent name: Meridian  
> Runtime: On-device MiniMax-M3  
> Privacy: Local-first  
> Next: Add memory

Relevant references:

- [Sana AI](https://mobbin.com/screens/eb1ecaaa-88f8-48e8-89cc-b2caa356c584) for agent creation framing.
- [ElevenLabs](https://mobbin.com/screens/664c7ce6-a63d-4b9d-92ae-8e7224b4693f) for setup screens that expose the created object's configuration.

### Step 5: Seed profile

Current issue:

- Name, nickname, and project are useful, but they read as profile fields.
- For Letta, these are memory seeds. The screen should make that explicit.

Recommended design:

- Rename the step to "Seed first memories."
- Convert user inputs into visible memory cards.
- Show where each memory will land after setup.

Concrete example:

Human memory:

> Emmanuel works on Letta mobile and prefers direct product feedback.

Project memory:

> Current project: desktop and onboarding redesign for Letta.

Preference memory:

> Prefers concrete UI references and practical iteration proposals.

Relevant references:

- [Linear onboarding flow](https://mobbin.com/flows/cbaf58a9-fd84-4afe-b214-e65b24484013) for turning setup answers into workspace configuration.
- [Productboard onboarding flow](https://mobbin.com/flows/54bce0a9-613c-4770-85e0-92c09b001e90) for product-context onboarding inputs.

### Step 6: Personality

Current issue:

- Six emoji-like tone options make the product feel more casual and older.
- Tone labels alone do not tell the user how the agent will behave.

Recommended design:

- Reduce to 3-4 presets.
- Show a sample response preview for the selected preset.
- Keep advanced personality editing for later.

Suggested presets:

- Focused
- Collaborative
- Technical
- Coach

Concrete interaction:

When "Technical" is selected, the preview answer becomes more precise and implementation-oriented. When "Coach" is selected, it becomes more guiding and reflective.

Relevant references:

- [Uxcel](https://mobbin.com/screens/38804fe0-c387-4914-9294-9afbf9c87fca) for educational/profile setup choices.
- [PlayAI creating an agent flow](https://mobbin.com/flows/cdb50e4c-e541-494b-8e1c-0d4e5df71ccd) for agent voice/personality setup patterns.

### Step 7: Capabilities

Current issue:

- The toggles are useful, but the feature names are raw.
- "Web fetch", "Code execution", "Memory", "Scheduling", and "Voice input" need clearer outcome framing.

Recommended grouping:

- Research: Web fetch
- Build: Code execution
- Remember: Memory
- Follow up: Scheduling
- Speak: Voice input

Each capability should include:

- Permission level
- Runtime support: local, server, or both
- A one-line example of what the agent can do with it

Relevant references:

- [Chatbase](https://mobbin.com/screens/4f106772-fa21-4410-a117-7a14910db626) for setup options tied to bot behavior.
- [ElevenLabs](https://mobbin.com/screens/88ed2c5d-b81f-4194-b364-e47aec63b506) for capability-style configuration.

### Step 8: Setting up and success

Current issue:

- The setup and success screens are clean but generic.
- Success still looks like onboarding, not the product.

Recommended design:

- Turn setup into a build log:
  - Creating agent profile
  - Writing core memory
  - Enabling selected tools
  - Preparing first chat
- Land directly in first-run chat with the configured agent.

Success should not be the end of onboarding. It should be the first useful product moment.

Relevant references:

- [Base44 creating a superagent flow](https://mobbin.com/flows/f49fea5a-49ef-4b9d-9286-9952494aee53) for moving quickly from creation into use.
- [Adaline](https://mobbin.com/screens/8ad4a415-bff7-4350-91af-75519784b729) for AI-product empty states that feel closer to the core workflow.
- [Lightfield](https://mobbin.com/screens/5b5f4b9f-5940-47a0-845d-f6c74bac4de8) for first-use AI screens with clearer product orientation.

## Page Surface Review

### Chat

Current read:

- Chat is the most mature surface.
- The flagship style has useful prompt chips and a clear composer.
- It still leans heavily on generic dark AI-product conventions.

Recommendations:

- Put agent state closer to the composer.
- Show memory/tool/runtime chips as functional status, not decoration.
- Make prompt chips reflect the current agent and onboarding data.
- Avoid oversized empty-center treatments once the user has history.

References:

- [Claude](https://mobbin.com/screens/bed62d52-5964-43df-9a48-46a6343f4241) for restrained chat information architecture.
- [Claude](https://mobbin.com/screens/16b71c74-387d-4aa8-83ca-8b542a5c4876) for keeping the conversation central.
- [ChatGPT](https://mobbin.com/screens/0b13adfe-2f77-4f65-ada6-c2a6aa092688) for tool/chat entry patterns.

### Memory

Current read:

- The grouped memory rows are clean and legible.
- The page still feels like a settings list.

Recommendations:

- Introduce memory cards with source, last updated, and confidence/edit affordances.
- Add a selected-memory inspector on wider screens.
- Let users see how onboarding seeds became memory.
- Group by purpose: Human, Agent, Project, Archival, Recent.

Reference:

- [Mixpanel](https://mobbin.com/screens/247110c8-7185-4ecf-a176-c33a47040cb5) for an information-heavy surface with a clearer inspection model.

### Tools

Current read:

- Search, enabled tools, available tools, and toggles are understandable.
- The page feels like a preference screen rather than a capability manager.

Recommendations:

- Treat enabled tools as active capabilities with permission and risk labels.
- Group available tools by job.
- Add "used recently" or "used by this agent" metadata.
- Use stronger state design for enabled, unavailable, requires server, and risky.

References:

- [ManyChat](https://mobbin.com/screens/bc7fc57c-7cdb-4af1-b361-fb98a0820879) for operational tool/workflow management.
- [ManyChat](https://mobbin.com/screens/2eaf030c-6046-4373-afc5-4d4ab99a9d6d) for dense but structured configuration surfaces.

### Schedules

Current read:

- The schedule direction is useful but currently appears sparse and unfinished.
- The task config board has too much empty placeholder space.

Recommendations:

- Reframe as an automation builder:
  - Trigger
  - Cadence
  - Agent
  - Instruction
  - Output destination
  - Preview
- Add templates like "daily digest", "weekly project review", and "follow up on stale tasks."
- Show the next run time and recent run status.

References:

- [Motion](https://mobbin.com/screens/54ef392d-6ac8-4ec4-bc02-20174c08da26) for task scheduling and planning structure.
- [Calendly](https://mobbin.com/screens/010463c7-1479-4b9b-bfaf-00df8e500795) for time-based setup clarity.
- [7shifts](https://mobbin.com/screens/f47c5587-bf8b-4143-9f10-37d097297f5b) for schedule operations and status density.

### Settings

Current read:

- Settings are readable and organized.
- They do not need to be expressive.
- The risk is that the settings visual language has leaked into pages that should be more product-specific.

Recommendations:

- Keep settings mostly plain.
- Use settings as the baseline utility pattern.
- Differentiate Memory, Tools, and Schedules away from this pattern.

References:

- [Wix](https://mobbin.com/screens/49a3d55e-ad99-4c6d-9e43-b41d5f3ebfe1) for admin settings density.
- [HoneyBook](https://mobbin.com/screens/a9e38208-cc5d-49ca-a943-a75180da1169) for work-focused settings surfaces.

## Recommended Proposal Page

Create a new proposal page with three hero boards.

### 1. Onboarding - Agent Builder

Goal:

Show the onboarding flow as a live agent-building experience.

Boards:

- Welcome with live agent preview
- Local/server path choice with tradeoffs
- Seed memory step with generated memory cards
- Personality step with response preview
- Capabilities step with grouped permissions
- Success state that lands in first-run chat

### 2. Desktop - First Agent Activated

Goal:

Upgrade the current first-run desktop board into the target post-onboarding moment.

Key elements:

- Quiet agent rail
- Hover-expanded agent identity tooltip
- Agent greeting
- Setup/readiness checklist
- Memory/tool/runtime status strip
- Starter prompts based on onboarding
- Composer ready for first useful action

### 3. Modernized Product Pages

Goal:

Show how Memory, Tools, and Schedules stop feeling like settings pages.

Boards:

- Memory as knowledge cards plus inspector
- Tools as capability catalog plus permissions
- Schedules as automation builder plus next-run preview

## Concrete Design Principles For The Next Iteration

1. Every onboarding step should visibly change the agent.
2. Every main page should reveal what the agent knows, can do, or will do next.
3. The agent rail should be quiet until the user needs identity or switching context.
4. Success should land in the product, not in another onboarding confirmation screen.
5. Settings-list UI should be reserved for settings, not used as the default structure for every page.
6. Teal should be one part of the system, not the only hierarchy mechanism.
7. Empty states should offer useful next actions, templates, or previews.

## Reference Appendix

### Onboarding and agent creation flows

- [Chatbase onboarding flow](https://mobbin.com/flows/e476c604-a53d-4fe3-a171-b6321a436049)
- [Sana AI creating an agent flow](https://mobbin.com/flows/fc62b881-df54-4a27-8155-a6b18503cbe3)
- [Base44 creating a superagent flow](https://mobbin.com/flows/f49fea5a-49ef-4b9d-9286-9952494aee53)
- [PlayAI creating an agent flow](https://mobbin.com/flows/cdb50e4c-e541-494b-8e1c-0d4e5df71ccd)
- [Linear onboarding flow](https://mobbin.com/flows/cbaf58a9-fd84-4afe-b214-e65b24484013)
- [Productboard onboarding flow](https://mobbin.com/flows/54bce0a9-613c-4770-85e0-92c09b001e90)
- [n8n joining a workspace flow](https://mobbin.com/flows/243292b2-c4f5-45aa-9c64-13963a516cf0)
- [Outseta onboarding flow](https://mobbin.com/flows/bd71adf0-7324-4769-b552-42b2cd5125e0)

### Onboarding and setup screens

- [Notion](https://mobbin.com/screens/92c06d8a-8eb3-4aa9-8b6a-20abdbade8cb)
- [Google Workspace](https://mobbin.com/screens/05a829a2-525b-4a6e-911a-f4d5e2e5b9ce)
- [Uxcel](https://mobbin.com/screens/38804fe0-c387-4914-9294-9afbf9c87fca)
- [Outseta](https://mobbin.com/screens/5d6f85be-812f-4932-96a5-34c4dcbd27a8)
- [Trello](https://mobbin.com/screens/82fbb95b-b8b6-4cd6-8da5-03baa6823456)
- [Klaviyo](https://mobbin.com/screens/e4d1f211-cd52-42a3-b835-556bf0508064)
- [Sana AI](https://mobbin.com/screens/eb1ecaaa-88f8-48e8-89cc-b2caa356c584)
- [Chatbase](https://mobbin.com/screens/a72f46fc-e9bf-4f8a-b7dd-bb34b8db4ea2)
- [ElevenLabs](https://mobbin.com/screens/664c7ce6-a63d-4b9d-92ae-8e7224b4693f)
- [Gorgias](https://mobbin.com/screens/6018dd21-9c3a-4680-aa69-e46176d821db)
- [ElevenLabs](https://mobbin.com/screens/88ed2c5d-b81f-4194-b364-e47aec63b506)
- [Chatbase](https://mobbin.com/screens/4f106772-fa21-4410-a117-7a14910db626)

### Desktop, chat, and work surfaces

- [ChatGPT](https://mobbin.com/screens/9ecd64ee-6c45-4183-945f-58aa27a29585)
- [ChatGPT](https://mobbin.com/screens/0b13adfe-2f77-4f65-ada6-c2a6aa092688)
- [Claude](https://mobbin.com/screens/6aaeec9e-bda5-49bd-91ad-a83106741fd7)
- [Claude](https://mobbin.com/screens/587db0a6-6f7d-4a6a-b1ac-80ea594f7c7a)
- [Claude](https://mobbin.com/screens/bed62d52-5964-43df-9a48-46a6343f4241)
- [Claude](https://mobbin.com/screens/16b71c74-387d-4aa8-83ca-8b542a5c4876)
- [Langdock](https://mobbin.com/screens/b66870ae-8d47-493d-b1b6-e6c01173ad96)
- [Langdock](https://mobbin.com/screens/b05495f9-e6dd-49f1-b87c-e5113e77ab44)
- [Linear](https://mobbin.com/screens/eebedb56-835f-42a5-bfc0-df67f2006bc5)
- [Linear](https://mobbin.com/screens/c288bb0f-7c2f-41fc-907c-7d3f69f1e848)
- [Linear](https://mobbin.com/screens/ed670cda-0527-4716-a1a6-0159f12c4f42)
- [Linear](https://mobbin.com/screens/3cc44869-3a72-44a1-8ff4-fbc12fcdccf9)

### Product surfaces, settings, and empty states

- [Mixpanel](https://mobbin.com/screens/9db2d964-8fb0-4d0e-a660-bb3a9893e328)
- [Mixpanel](https://mobbin.com/screens/c84d8d04-4a1f-4cc2-8ea6-8c353a8238a9)
- [Mixpanel](https://mobbin.com/screens/247110c8-7185-4ecf-a176-c33a47040cb5)
- [Motion](https://mobbin.com/screens/54ef392d-6ac8-4ec4-bc02-20174c08da26)
- [Calendly](https://mobbin.com/screens/010463c7-1479-4b9b-bfaf-00df8e500795)
- [7shifts](https://mobbin.com/screens/f47c5587-bf8b-4143-9f10-37d097297f5b)
- [ManyChat](https://mobbin.com/screens/bc7fc57c-7cdb-4af1-b361-fb98a0820879)
- [ManyChat](https://mobbin.com/screens/2eaf030c-6046-4373-afc5-4d4ab99a9d6d)
- [Wix](https://mobbin.com/screens/49a3d55e-ad99-4c6d-9e43-b41d5f3ebfe1)
- [HoneyBook](https://mobbin.com/screens/a9e38208-cc5d-49ca-a943-a75180da1169)
- [Lightfield](https://mobbin.com/screens/5b5f4b9f-5940-47a0-845d-f6c74bac4de8)
- [Adaline](https://mobbin.com/screens/8ad4a415-bff7-4350-91af-75519784b729)
- [Superlist](https://mobbin.com/screens/36d518e7-e12a-4a16-8490-9df86113fc99)
- [Apollo](https://mobbin.com/screens/1dc11d19-505d-4259-81a5-a55cc4948e89)
- [Apollo](https://mobbin.com/screens/6ef26211-448f-480b-8fe8-2c0d82c94f13)
