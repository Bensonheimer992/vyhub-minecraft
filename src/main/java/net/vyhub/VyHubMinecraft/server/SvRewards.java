package net.vyhub.VyHubMinecraft.server;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import net.vyhub.VyHubMinecraft.Entity.AppliedReward;
import net.vyhub.VyHubMinecraft.Entity.Reward;
import net.vyhub.VyHubMinecraft.Entity.VyHubUser;
import net.vyhub.VyHubMinecraft.VyHub;
import net.vyhub.VyHubMinecraft.event.VyHubPlayerInitializedEvent;
import net.vyhub.VyHubMinecraft.lib.Cache;
import net.vyhub.VyHubMinecraft.lib.Types;
import net.vyhub.VyHubMinecraft.lib.Utility;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Type;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.logging.Level;

public class SvRewards implements Listener {

    private static Map<String, List<AppliedReward>> rewards;
    private static List<String> executedRewards = new ArrayList<>();
    private static List<String> executedAndSentRewards = new ArrayList<>();

    private static Cache<List<String>> rewardCache = new Cache<>(
            "executed_rewards",
            new TypeToken<ArrayList<String>>() {
            }.getType()
    );


    public static void getRewards() {
        StringBuilder stringBuilder = new StringBuilder();

        for (Player player : Bukkit.getOnlinePlayers()) {
            VyHubUser user = SvUser.getUser(player.getUniqueId().toString());

            if (user != null) {
                stringBuilder.append("user_id=").append(user.getId()).append("&");
            }
        }

        if (stringBuilder.toString().length() != 0) {
            stringBuilder.deleteCharAt(stringBuilder.length() - 1);
        }

        HttpResponse<String> resp = Utility.sendRequest("/packet/reward/applied/user?active=true&foreign_ids=true&status=OPEN&serverbundle_id=" + SvServer.serverbundleID + "&for_server_id=" + VyHub.config.get("serverID") + "&" +
                stringBuilder, Types.GET);

        if (resp != null && resp.statusCode() == 200) {
            Gson gson = new Gson();
            Type userRewardType = new TypeToken<Map<String, List<AppliedReward>>>() {
            }.getType();

            rewards = gson.fromJson(resp.body(), userRewardType);
        }
    }

    public static void getPlayerReward(Player player) {
        VyHubUser user = SvUser.getUser(player.getUniqueId().toString());

        if (user == null) {
            return;
        }

        if (rewards == null) {
            rewards = new HashMap<>();
        }

        HttpResponse<String> resp = Utility.sendRequest("/packet/reward/applied/user?active=true&foreign_ids=true&status=OPEN&serverbundle_id=" + SvServer.serverbundleID + "&for_server_id=" + VyHub.config.get("serverID") +
                "&user_id=" +
                user.getId(), Types.GET);

        if (resp != null && resp.statusCode() == 200) {
            Gson gson = new Gson();
            Type userRewardType = new TypeToken<Map<String, List<AppliedReward>>>() {
            }.getType();

            Map<String, List<AppliedReward>> playerRewards = gson.fromJson(resp.body(), userRewardType);
            rewards.put(player.getUniqueId().toString(), playerRewards.getOrDefault(player.getUniqueId().toString(), new ArrayList<>()));
        }
    }

    public static synchronized void executeReward(List<String> events, String playerID) {
        if (rewards == null) {
            return;
        }

        Map<String, List<AppliedReward>> rewardsByPlayer = new HashMap<>(rewards);

        if (playerID == null) {
            for (String event : events) {
                if (!event.equals("DIRECT") && !event.equals("DISABLE")) {
                    throw new RuntimeException();
                }
            }
        } else {
            rewardsByPlayer.clear();

            if (rewards.containsKey(playerID)) {
                rewardsByPlayer.put(playerID, rewards.get(playerID));
            } else {
                return;
            }
        }

        for (Map.Entry<String, List<AppliedReward>> entry : rewardsByPlayer.entrySet()) {
            String _playerID = entry.getKey();
            List<AppliedReward> appliedRewards = entry.getValue();

            Player player = Bukkit.getPlayer(UUID.fromString(_playerID));

            if (player == null) {
                continue;
            }

            for (AppliedReward appliedReward : appliedRewards) {
                if (executedRewards.contains(appliedReward.getId()) || executedAndSentRewards.contains(appliedReward.getId())) {
                    continue;
                }

                Reward reward = appliedReward.getReward();
                if (events.contains(reward.getOn_event())) {
                    Map<String, String> data = reward.getData();
                    boolean success = true;
                    if (reward.getType().equals("COMMAND")) {
                        String command = data.get("command");
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stringReplace(command, player, appliedReward.getApplied_packet_id()));
                    } else {
                        success = false;

                        Bukkit.getServer().getLogger().log(Level.WARNING, "No implementation for Reward Type: " + reward.getType());
                    }
                    if (reward.getOnce()) {
                        setExecuted(appliedReward.getId());
                    }
                    if (success) {
                        Bukkit.getServer().getLogger().log(Level.INFO, "RewardName: " + appliedReward.getReward().getName() + " Type: " +
                                appliedReward.getReward().getType() + " Player: " + player.getName() + " executed!");
                    }
                }
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(VyHub.getPlugin(VyHub.class), SvRewards::sendExecuted);
    }

    public static synchronized void setExecuted(String id) {
        executedRewards.add(id);
        saveExecuted();
    }

    public static synchronized void saveExecuted() {
        rewardCache.save(executedRewards);
    }

    public static synchronized void sendExecuted() {
        List<String> serverID = new ArrayList<>();
        serverID.add(VyHub.config.get("serverID"));

        List<String> newExecutedAndSentRewards = new ArrayList<>();
        HashMap<String, Object> values = new HashMap<>() {{
            put("executed_on", serverID);
        }};

        for (Iterator<String> it = executedRewards.iterator(); it.hasNext(); ) {
            String rewardID = it.next();
            HttpResponse<String> response = Utility.sendRequestBody("/packet/reward/applied/" + rewardID, Types.PATCH, Utility.createRequestBody(values));

            if (response != null && response.statusCode() == 200) {
                newExecutedAndSentRewards.add(rewardID);
                it.remove();
                saveExecuted();
            }
        }

        executedAndSentRewards = newExecutedAndSentRewards;
    }

    public static synchronized void loadExecuted() {
        executedRewards = rewardCache.load();

        if (executedRewards == null) {
            executedRewards = new ArrayList<>();
        }
    }

    public static void runDirectRewards() {
        List<String> eventList = new LinkedList<>();
        eventList.add("DIRECT");
        eventList.add("DISABLE");

        executeReward(eventList, null);
    }


    @EventHandler
    public void onPlayerInit(VyHubPlayerInitializedEvent event) {
        Player player = event.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                getPlayerReward(player);

                List<String> eventList = new LinkedList<>();
                eventList.add("CONNECT");
                eventList.add("SPAWN");

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        executeReward(eventList, player.getUniqueId().toString());
                    }
                }.runTask(VyHub.plugin);
            }
        }.runTaskAsynchronously(VyHub.plugin);
    }

    @EventHandler
    public void onSpawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        List<String> eventList = new LinkedList<>();
        eventList.add("SPAWN");

        new BukkitRunnable() {
            public void run() {
                executeReward(eventList, player.getUniqueId().toString());
            }
        }.runTaskLater(VyHub.getPlugin(VyHub.class), 20);

    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        List<String> eventList = new LinkedList<>();
        eventList.add("DEATH");

        executeReward(eventList, player.getUniqueId().toString());
    }

    @EventHandler
    public void onDisconnect(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        List<String> eventList = new LinkedList<>();
        eventList.add("DISCONNECT");

        executeReward(eventList, player.getUniqueId().toString());
    }

    public static String stringReplace(String command, Player player, String appliedPacketId) {
        String newString = command;
        newString = newString.replace("%nick%", player.getName());
        newString = newString.replace("%user_id%", SvUser.getUser(player.getUniqueId().toString()).getId());
        newString = newString.replace("%applied_packet_id%", appliedPacketId);
        newString = newString.replace("%player_id%", player.getUniqueId().toString());
        newString = newString.replace("%player_ip_address%", player.getAddress().getAddress().toString().replace("/", ""));

        return newString;
    }

}
