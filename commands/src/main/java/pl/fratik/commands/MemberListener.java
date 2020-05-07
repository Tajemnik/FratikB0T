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

package pl.fratik.commands;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberLeaveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import pl.fratik.core.entity.GuildConfig;
import pl.fratik.core.entity.GuildDao;
import pl.fratik.core.event.DatabaseUpdateEvent;
import pl.fratik.core.util.CommonUtil;
import pl.fratik.core.util.UserUtil;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

class MemberListener {
    private final GuildDao guildDao;
    private final Cache<String, GuildConfig> gcCache = Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).build();

    MemberListener(GuildDao guildDao) {
        this.guildDao = guildDao;
    }

    @Subscribe
    public void onMemberJoinEvent(GuildMemberJoinEvent e) {
        autorole(e);
        przywitanie(e);
    }

    private void autorole(GuildMemberJoinEvent e) {
        GuildConfig gc = getGuildConfig(e.getGuild());
        List<Role> role = new ArrayList<>();
        if (gc.getAutoroleZa1szaWiadomosc()) return;
        for (String id : gc.getAutorole()) {
            Role rola = CommonUtil.supressException((Function<String, Role>) e.getGuild()::getRoleById, id);
            if (rola == null || !e.getGuild().getSelfMember().canInteract(rola)) continue;
            role.add(rola);
        }
        if (role.isEmpty()) return;
        e.getGuild().modifyMemberRoles(e.getMember(), role, new ArrayList<>()).queue();
    }

    @Subscribe
    public void onMemberLeaveEvent(GuildMemberLeaveEvent e) {
        GuildConfig gc = getGuildConfig(e.getGuild());
        generateEmbed(e.getMember(), e.getGuild(), gc.getPozegnania().entrySet());
    }

    private void przywitanie(GuildMemberJoinEvent e) {
        GuildConfig gc = getGuildConfig(e.getGuild());

        if (gc.getPowitanieWEmbedzie()) generateEmbed(e.getMember(), e.getGuild(), gc.getPowitania().entrySet());
        else {
            for (Map.Entry<String, String> ch : gc.getPowitania().entrySet()) {
                TextChannel cha = e.getGuild().getTextChannelById(ch.getKey());
                if (cha == null || !cha.canTalk()) continue;
                cha.sendMessage(getPowitanie(e.getMember(), ch.getValue())).queue();
            }
        }
    }

    private void generateEmbed(Member user, Guild guild, Set<Map.Entry<String, String>> kek) { // L125
        EmbedBuilder eb = new EmbedBuilder();
        String avatar = UserUtil.getAvatarUrl(user.getUser());

        for (Map.Entry<String, String> ch : kek) {
            TextChannel cha = guild.getTextChannelById(ch.getKey());
            if (cha == null || !cha.canTalk()) continue;
            eb.setAuthor(UserUtil.formatDiscrim(user), avatar);
            eb.setDescription(getPowitanie(user, ch.getValue()));
            eb.setColor(UserUtil.getPrimColor(user.getUser()));
            cha.sendMessage(eb.build()).queue();
            break;
        }
    }

    private GuildConfig getGuildConfig(Guild guild) {
        return gcCache.get(guild.getId(), guildDao::get);
    }

    @Subscribe
    @AllowConcurrentEvents
    public void onMessage(MessageReceivedEvent m) {
        if (m.getChannelType() != ChannelType.TEXT || m.isWebhookMessage()) return;
        GuildConfig gc = getGuildConfig(m.getGuild());
        if (!gc.getAutoroleZa1szaWiadomosc()) return;
        List<Role> role = new ArrayList<>();
        for (String id : gc.getAutorole()) {
            Role rola = CommonUtil.supressException((Function<String, Role>) m.getGuild()::getRoleById, id);
            if (rola == null || !m.getGuild().getSelfMember().canInteract(rola)) continue;
            role.add(rola);
        }
        if (role.isEmpty()) return;
        if (role.stream().anyMatch(Objects.requireNonNull(m.getMember()).getRoles()::contains)) return;
        try {
            m.getGuild().modifyMemberRoles(m.getMember(), role, new ArrayList<>()).queue();
        } catch (Exception e) {
            // nie mamy permów, zawijamy się
        }
    }

    private String getPowitanie(Member user, String msg) { // jestem zbyt leniwy żeby zmienić na Member member
        return msg
                .replaceAll("\\{\\{user}}", user.getUser().getAsTag().replaceAll("@(everyone|here)", "@\u200b$1"))
                .replaceAll("\\{\\{mention}}", user.getAsMention())
                .replaceAll("\\{\\{members}}", String.valueOf(user.getGuild().getMembers().size()))
                .replaceAll("\\{\\{server}}", user.getGuild().getName().replaceAll("@(everyone|here)", "@\u200b$1"));
    }

    @Subscribe
    public void onDatabaseUpdateEvent(DatabaseUpdateEvent e) {
        if (e.getEntity() instanceof GuildConfig)
            gcCache.put(((GuildConfig) e.getEntity()).getGuildId(), (GuildConfig) e.getEntity());
    }
}