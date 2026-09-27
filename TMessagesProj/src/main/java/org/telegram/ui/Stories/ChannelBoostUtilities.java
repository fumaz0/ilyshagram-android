package org.telegram.ui.Stories;

import android.text.TextUtils;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

public class ChannelBoostUtilities {
    public static String createLink(int currentAccount, long dialogId) {
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        String username = ChatObject.getPublicUsername(chat);
        // linkPrefix is this account's own server domain (me_url_prefix); must not be
        // hardcoded to t.me, or self-hosted boost links point at official Telegram.
        String linkPrefix = MessagesController.getInstance(currentAccount).linkPrefix;
        if (!TextUtils.isEmpty(username)) {
            return "https://" + linkPrefix + "/boost/" + ChatObject.getPublicUsername(chat);
        } else {
            return "https://" + linkPrefix + "/boost/?c=" + -dialogId;
        }
    }
}
