# Lane inbox v1: Implementation notes (H4)

The host journals requests from verified callers and holds authenticated matching
replies for the originating agent and conversation. A held reply starts no turn.
The wrapper prepends a delimited block labelled untrusted, with host-owned
`reply_from` and `correlation_id`, to the next input. The body is JSON quoted so
body text cannot close the host block. The user input follows unchanged.
Consumption follows App Server acceptance; refusal leaves the reply held.

A turn that does not enter through the wrapper, including an App Server scheduled
wake, does not carry the block. The reply waits for a wrapper-routed input.
`await_reply` and the digest line are outside this stage. Cross-host identity trust
is outside this stage; reply requests require a known local replier identity.

App Server acceptance and local journal consumption are separate durable writes.
A crash between them requires reconciliation to guarantee exactly-once attachment
across that failure window; the local callback alone cannot provide that guarantee.
