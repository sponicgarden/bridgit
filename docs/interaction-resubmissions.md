# Revisiting answered interactions

Answered questions in history offer **Back to questions**. Android Back and the toolbar Back also return from an answer confirmation to the question. A second Back leaves the interaction.

Native questions restore the previously submitted text and choice values. HTML cards reload the original HTML so even a custom confirmation that replaced the whole document can be revisited. HTML answers receive the cancelable `bridgit:answered` DOM event after persistence, with `detail.answer` and `detail.close_after_ms`. Cards may prevent the default event to draw their own confirmation. The fallback stays inside the sandbox. Fresh answers close after five seconds; reopened history has no countdown, and Back to questions cancels pending auto-close.

Submitting again stores the latest answer and emits another `interaction.answered` event containing `resubmission: true` and `previous_answer`, alongside the usual `interaction_id` and `answer`. Earlier answer events remain subject to normal event retention. Agents should use the events cursor to observe repeat answers and decide whether to accept the requested change. Delivery to Bridgit does not mean that an agent accepted a change or repeated an external action.

Resubmission requires the owner to reopen the question. Existing notification actions cannot overwrite an answered interaction. Cancelled, dismissed and expired interactions remain closed.
