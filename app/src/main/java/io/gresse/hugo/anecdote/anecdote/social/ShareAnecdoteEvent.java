package io.gresse.hugo.anecdote.anecdote.social;

import io.gresse.hugo.anecdote.anecdote.model.Anecdote;

/**
 * Launched to share an anecdote
 *
 * Created by Hugo Gresse on 20/07/16.
 */
public class ShareAnecdoteEvent extends SocialEvent {

    public String shareString;

    public ShareAnecdoteEvent(String websiteName, Anecdote anecdote, String shareString) {
        super(websiteName, anecdote);
        this.shareString = shareString;
    }

}
