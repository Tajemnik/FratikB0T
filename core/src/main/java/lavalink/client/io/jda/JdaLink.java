/*
 * Copyright (C) 2019-2020 FratikB0T Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package lavalink.client.io.jda;

import lavalink.client.io.Link;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class JdaLink extends Link {

    private static final Logger log = LoggerFactory.getLogger(JdaLink.class);
    private final JdaLavalink lavalink;

    protected JdaLink(JdaLavalink lavalink, String guildId) {
        super(lavalink, guildId);
        this.lavalink = lavalink;
    }

    public void connect(@Nonnull AudioChannel voiceChannel) {
        connect(voiceChannel, true);
    }

    /**
     * Eventually connect to a channel. Takes care of disconnecting from an existing connection
     *
     * @param channel Channel to connect to
     */
    @SuppressWarnings("WeakerAccess")
    void connect(@Nonnull AudioChannel channel, boolean checkChannel) {
        if (!channel.getGuild().equals(getJda().getGuildById(guild)))
            throw new IllegalArgumentException("The provided VoiceChannel is not a part of the Guild that this AudioManager handles." +
                    "Please provide a VoiceChannel from the proper Guild");
        if (channel.getJDA().isUnavailable(channel.getGuild().getIdLong()))
            throw new IllegalStateException("Cannot open an Audio Connection with an unavailable guild. " +
                    "Please wait until this Guild is available to open a connection.");
        final Member self = channel.getGuild().getSelfMember();
        if (!self.hasPermission(channel, Permission.VOICE_CONNECT) && !self.hasPermission(channel, Permission.VOICE_MOVE_OTHERS))
            throw new InsufficientPermissionException(channel, Permission.VOICE_CONNECT);

        //If we are already connected to this VoiceChannel, then do nothing.
        GuildVoiceState voiceState = channel.getGuild().getSelfMember().getVoiceState();
        if (voiceState == null) return;

        if (checkChannel && channel.equals(voiceState.getChannel()))
            return;

        if (voiceState.inAudioChannel()) {
            final int userLimit = channel instanceof VoiceChannel ? ((VoiceChannel) channel).getUserLimit() : 0;
            // userLimit is 0 if no limit is set!
            if (!self.isOwner() && !self.hasPermission(Permission.ADMINISTRATOR)) {
                if (userLimit > 0                                                      // If there is a userlimit
                        && userLimit <= channel.getMembers().size()                    // if that userlimit is reached
                        && !self.hasPermission(channel, Permission.VOICE_MOVE_OTHERS)) // If we don't have voice move others permissions
                    throw new InsufficientPermissionException(channel, Permission.VOICE_MOVE_OTHERS, // then throw exception!
                            "Unable to connect to VoiceChannel due to userlimit! Requires permission VOICE_MOVE_OTHERS to bypass");
            }
        }

        setState(State.CONNECTING);
        queueAudioConnect(channel.getIdLong());
    }

    @SuppressWarnings("WeakerAccess")
    @Nonnull
    public JDA getJda() {
        return lavalink.getJdaFromSnowflake(String.valueOf(guild));
    }

    @Override
    protected void removeConnection() {
        // JDA handles this for us
    }

    @Override
    protected void queueAudioDisconnect() {
        Guild g = getJda().getGuildById(guild);

        if (g != null) {
            getJda().getDirectAudioController().disconnect(g);
        } else {
            log.warn("Attempted to disconnect, but guild {} was not found", guild);
        }
    }

    @Override
    protected void queueAudioConnect(long channelId) {
        VoiceChannel vc = getJda().getVoiceChannelById(channelId);
        if (vc != null) {
            getJda().getDirectAudioController().connect(vc);
        } else {
            log.warn("Attempted to connect, but voice channel {} was not found", channelId);
        }
    }

    /**
     * @return the Guild, or null if it doesn't exist
     */
    @SuppressWarnings("WeakerAccess")
    @Nullable
    public Guild getGuild() {
        return getJda().getGuildById(guild);
    }
}
