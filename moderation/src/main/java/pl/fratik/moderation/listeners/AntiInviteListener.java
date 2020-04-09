/*
 * Copyright (C) 2019 FratikB0T Contributors
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

package pl.fratik.moderation.listeners;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageUpdateEvent;
import net.dv8tion.jda.api.sharding.ShardManager;
import pl.fratik.core.command.PermLevel;
import pl.fratik.core.entity.GuildConfig;
import pl.fratik.core.entity.GuildDao;
import pl.fratik.core.entity.Kara;
import pl.fratik.core.event.DatabaseUpdateEvent;
import pl.fratik.core.manager.ManagerKomend;
import pl.fratik.core.tlumaczenia.Tlumaczenia;
import pl.fratik.core.util.UserUtil;
import pl.fratik.moderation.entity.Case;
import pl.fratik.moderation.entity.CaseBuilder;
import pl.fratik.moderation.entity.CaseRow;
import pl.fratik.moderation.entity.CasesDao;
import pl.fratik.moderation.utils.ModLogBuilder;
import pl.fratik.moderation.utils.WarnUtil;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AntiInviteListener {

    private final GuildDao guildDao;
    private final Tlumaczenia tlumaczenia;
    private final ManagerKomend managerKomend;
    private final ShardManager shardManager;
    private final CasesDao casesDao;
    private static final Cache<String, Boolean> antiinviteCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(10, TimeUnit.MINUTES).build();
    private static final Cache<String, List<String>> antiinviteIgnoreChannelsCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(10, TimeUnit.MINUTES).build();
    private static final Cache<String, String> modlogCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(10, TimeUnit.MINUTES).build();

    public AntiInviteListener(GuildDao guildDao, Tlumaczenia tlumaczenia, ManagerKomend managerKomend, ShardManager shardManager, CasesDao casesDao) {
        this.guildDao = guildDao;
        this.tlumaczenia = tlumaczenia;
        this.managerKomend = managerKomend;
        this.shardManager = shardManager;
        this.casesDao = casesDao;
    }

    @Subscribe
    @AllowConcurrentEvents
    public void onMessage(MessageReceivedEvent e) {
        if (!e.isFromType(ChannelType.TEXT) || e.getMember() == null || e.getAuthor().isBot() ||
                !e.getTextChannel().canTalk()) return;
        if (!isAntiinvite(e.getGuild()) || isIgnored(e.getTextChannel())) return;
        if (!e.getGuild().getSelfMember().canInteract(e.getMember())) return;
        if (UserUtil.getPermlevel(e.getMember(), guildDao, shardManager, PermLevel.OWNER).getNum() >= 1) return;

        if (containsInvite(e.getMessage().getContentRaw())) addKara(e.getMessage());
    }

    @Subscribe
    @AllowConcurrentEvents
    public void onEdit(GuildMessageUpdateEvent e) {
        if (e.getMember() == null || e.getAuthor().isBot() ||
                !e.getChannel().canTalk()) return;
        if (!isAntiinvite(e.getGuild()) || isIgnored(e.getChannel())) return;
        if (!e.getGuild().getSelfMember().canInteract(e.getMember())) return;
        if (UserUtil.getPermlevel(e.getMember(), guildDao, shardManager, PermLevel.OWNER).getNum() >= 1) return;

        if (containsInvite(e.getMessage().getContentRaw())) addKara(e.getMessage());
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isAntiinvite(Guild guild) {
        //noinspection ConstantConditions - nie moze byc null
        return antiinviteCache.get(guild.getId(), id -> guildDao.get(guild).getAntiInvite());
    }

    private boolean isIgnored(TextChannel channel) {
        //noinspection ConstantConditions - nie moze byc null
        return antiinviteIgnoreChannelsCache.get(channel.getGuild().getId(),
                id -> guildDao.get(id).getKanalyGdzieAntiInviteNieDziala()).contains(channel.getId());
    }

    private String getModLogChan(Guild guild) {
        return modlogCache.get(guild.getId(), id -> guildDao.get(guild).getModLog());
    }

    private boolean containsInvite(String s) {
        s = s.toLowerCase();
        return s.contains("discord.gg/") || s.contains("discord.io/") || s.contains("discord.me/") ||
                s.contains("discordapp.com/invite/");
    }

    private synchronized void addKara(Message msg) {
        assert containsInvite(msg.getContentRaw());
        String trans = msg.isEdited() ? "antiinvite.notice.edited" : "antiinvite.notice";
        try {
            msg.delete().queue();
            synchronized (msg.getGuild()) {
                Case c = new CaseBuilder(msg.getGuild()).setUser(msg.getAuthor().getId()).setKara(Kara.WARN)
                        .setTimestamp(Instant.now()).createCase();
                c.setIssuerId(msg.getJDA().getSelfUser());
                c.setReason(tlumaczenia.get(tlumaczenia.getLanguage(msg.getGuild()), "antiinvite.reason"));
                msg.getChannel().sendMessage(tlumaczenia.get(tlumaczenia.getLanguage(msg.getMember()),
                        trans, msg.getAuthor().getAsMention(),
                        managerKomend.getPrefixes(msg.getGuild()).get(0))).queue();
                String mlogchan = getModLogChan(msg.getGuild());
                if (mlogchan == null || mlogchan.equals("")) mlogchan = "0";
                TextChannel mlogc = shardManager.getTextChannelById(mlogchan);

                if (!(mlogc == null || !mlogc.getGuild().getSelfMember().hasPermission(mlogc,
                        Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_WRITE, Permission.MESSAGE_READ))) {
                    Message m = mlogc.sendMessage(ModLogBuilder.generate(c,
                            msg.getGuild(), shardManager, tlumaczenia.getLanguage(msg.getGuild()), managerKomend)).complete();
                    c.setMessageId(m.getId());
                }
                CaseRow cr = casesDao.get(msg.getGuild());
                cr.getCases().add(c);
                casesDao.save(cr);
                Member m = msg.getMember();
                if (m == null) {
                    // to się nie stanie
                    return;
                }
                WarnUtil.takeAction(guildDao, casesDao, m, msg.getChannel(),
                        tlumaczenia.getLanguage(msg.getGuild()), tlumaczenia, managerKomend);
            }

        } catch (Exception ignored) {
            // no i chuj, wylądował, wszystko poszło w pizdu
        }
    }


    @Subscribe
    public void onDatabaseUpdate(DatabaseUpdateEvent e) {
        if (!(e.getEntity() instanceof GuildConfig)) return;
        antiinviteCache.invalidate(((GuildConfig) e.getEntity()).getGuildId());
    }
}
