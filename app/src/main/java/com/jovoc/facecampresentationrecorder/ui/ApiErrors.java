package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.account.AccountException;

/** One wording for failures of the workspace / balance / devices calls. */
final class ApiErrors {

    private ApiErrors() {}

    static String message(Context c, AccountException e) {
        switch (e.kind) {
            case NETWORK:
                return c.getString(R.string.err_offline);
            case SIGNED_OUT:
                return c.getString(R.string.acct_err_signed_out);
            case RATE_LIMITED:
                return e.retryAfterSeconds > 0
                        ? c.getString(R.string.err_rate_limited, SignInActivity.formatWait(e.retryAfterSeconds))
                        : c.getString(R.string.err_rate_limited_unknown);
            case WORKSPACE_FORBIDDEN:
                return c.getString(R.string.acct_err_workspace);
            case NOT_FOUND:
                return c.getString(R.string.acct_err_gone);
            default:
                return c.getString(R.string.err_server);
        }
    }
}
