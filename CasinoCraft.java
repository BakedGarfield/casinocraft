package be.wevelgem.casinocraft;

import com.google.gson.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.*;
import java.util.*;

/**
 * CasinoCraft GUI preview. All player actions are gated by a right-click on a registered block.
 * Only admins may register blocks / configure the horse track.
 * This file intentionally uses vanilla text buttons to avoid client-side dependencies.
 */
public final class CasinoCraft implements ModInitializer {
    private static final String[] TYPES = {"slots", "blackjack", "roulette", "horse", "higherlower"};
    private static final String[] NAMES = {"Bliksem", "Storm", "Turbo", "Schaduw", "Rocket"};
    private static final int[] DEFAULT_WEIGHTS = {40, 25, 17, 11, 7};
    private static final int[] DEFAULT_MULTIPLIERS = {2, 3, 4, 6, 10};
    // RGB colors for dyed leather horse armor; also used in the betting chat menu.
    private static final int[] DEFAULT_HORSE_COLORS = {0xFFD700, 0x3098F0, 0xEB3434, 0x9A50E0, 0x30BB60};
    private static final String[] COLOR_LABELS = {"Geel", "Blauw", "Rood", "Paars", "Groen"};
    // European single-zero roulette: true wheel/table coloring, independent of parity.
    // Real European single-zero wheel order, clockwise starting at zero.
    private static final int[] EUROPEAN_WHEEL = {
        0,32,15,19,4,21,2,25,17,34,6,27,13,36,11,30,8,23,10,
        5,24,16,33,1,20,14,31,9,22,18,29,7,28,12,35,3,26
    };
    private static final Set<Integer> RED_NUMBERS = Set.of(
        1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    private final Map<String,String> blocks = new HashMap<>();
    private final Map<UUID,Session> sessions = new HashMap<>();
    private final Map<String,RouletteTable> rouletteTables = new HashMap<>();
    private static final String ROULETTE_TAG = "casinocraft_roulette_hologram";
    private RouletteConfig rouletteConfig = new RouletteConfig();
    private boolean cleanedStartupDisplays;

    /** Loaded from config/casinocraft/roulette.json on server startup. */
    private static final class RouletteConfig {
        int bettingSeconds = 30;
        int minimumSpinSeconds = 15;
        int maximumSpinSeconds = 30;
        int zeroWeight = 2;
        int numberPayout = 36, zeroPayout = 18, outsidePayout = 2, groupPayout = 3;
        int maxTotal = 256, maxChips = 64;
        int redColor = 0xFF5555, blackColor = 0x555555, greenColor = 0x55FF55;
        int resultDisplaySeconds = 10;
        String title = "✦ ROULETTE ✦";
        String bettingText = "INZETTEN SLUITEN OVER {seconds}s";
        String spinningText = "WIEL DRAAIT...";
        String resultText = "WINNAAR: {number} {color}";
        String betsText = "{bets} inzetten geplaatst";
    }

    private Path rouletteConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("casinocraft").resolve("roulette.json");
    }
    private boolean loadRouletteConfig() {
        Path path = rouletteConfigPath();
        try {
            Files.createDirectories(path.getParent());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            if (!Files.exists(path)) Files.writeString(path, gson.toJson(new RouletteConfig()));
            RouletteConfig loaded = gson.fromJson(Files.readString(path), RouletteConfig.class);
            if (loaded == null) throw new IllegalArgumentException("empty config");
            if (loaded.bettingSeconds < 5 || loaded.bettingSeconds > 600 ||
                loaded.minimumSpinSeconds < 1 || loaded.maximumSpinSeconds > 30 ||
                loaded.maximumSpinSeconds < loaded.minimumSpinSeconds ||
                loaded.resultDisplaySeconds < 0 || loaded.resultDisplaySeconds > 600)
                throw new IllegalArgumentException("invalid roulette times");
            if (loaded.zeroWeight < 1 || loaded.zeroWeight > 100 || !validPayout(loaded.numberPayout) || !validPayout(loaded.zeroPayout) || !validPayout(loaded.outsidePayout) || !validPayout(loaded.groupPayout) || loaded.maxTotal < 1 || loaded.maxTotal > 4096 || loaded.maxChips < 1 || loaded.maxChips > 256 || !validColor(loaded.redColor) || !validColor(loaded.blackColor) || !validColor(loaded.greenColor))
                throw new IllegalArgumentException("invalid roulette payout, odds, limit or color");
            if (loaded.title == null || loaded.bettingText == null || loaded.spinningText == null ||
                loaded.resultText == null || loaded.betsText == null)
                throw new IllegalArgumentException("missing roulette text");
            Files.writeString(path, gson.toJson(loaded));
            rouletteConfig = loaded;
            System.out.println("[CasinoCraft] Roulette config loaded: " + path);
            return true;
        } catch (Exception ex) {
            System.err.println("[CasinoCraft] Invalid roulette config, keeping previous settings: " + ex);
            return false;
        }
    }
    private String rouletteText(String template, RouletteTable table, int seconds, int number) {
        return template.replace("{seconds}", Integer.toString(seconds))
            .replace("{bets}", Integer.toString(table.bets.size()))
            .replace("{number}", Integer.toString(number))
            .replace("{color}", number < 0 ? "" : tr(rouletteColor(number).toUpperCase(Locale.ROOT)));
    }
    private final Map<UUID,SlotSpin> slotSpins = new HashMap<>();
    private final Map<UUID,CasinoMenu> openMenus = new HashMap<>();
    private final Map<UUID,Long> unpaid = new HashMap<>();
    private final List<Bet> bets = new ArrayList<>();
    private final Random random = new Random();
    private final int[] weights = DEFAULT_WEIGHTS.clone(), multipliers = DEFAULT_MULTIPLIERS.clone(), horseColors = DEFAULT_HORSE_COLORS.clone();
    private final List<Horse> runners = new ArrayList<>();
    private MinecraftServer server;
    private BlockPos trackCenter;
    private String trackDimension = "minecraft:overworld";
    // Outermost lane's centerline radii; inner four lanes offset by laneWidth.
    private double trackRadiusX = 28.0, trackRadiusZ = 21.0, laneWidth = 3.0;
    private int tick, countdown = -1, raceTicks = -1, chosenWinner = -1;
    private final double[] raceProgress = new double[5];
    // Each lane has a lookup table of equally spaced distances around its ellipse.
    // Mapping time -> distance avoids horses speeding up on the narrow bends.
    private static final int RACE_PATH_STEPS = 720;
    private final double[][] laneDistance = new double[5][RACE_PATH_STEPS + 1];
    private final double[] laneLength = new double[5];
    private boolean dirty;

    private record RouletteChip(String choice, int amount) {}
    

    private static final class Session {
        final String type;
        final String block;
        int stake = 1, selected = 0, value, dealer, rounds;
        String resultTitle, resultDetail;
        int[] lastSlotSymbols;
        String lastSlotTitle, lastSlotDetail;
        int resultExpiresAt = -1;
        boolean doubled;
        String roulettePage = "main";
        final List<RouletteChip> rouletteChips = new ArrayList<>();
        Session(String type, String block) { this.type=type; this.block=block; }
    }
    private record Bet(UUID player, int horse, int stake, int multiplier) {}
    private record TableBet(UUID player, String choice, int amount) {}
    private static final class RouletteTable {
        final String block;
        final List<TableBet> bets = new ArrayList<>();
        int seconds = -1; // betting countdown; -1 during spin/idle
        int spinSeconds = -1; // spinning countdown; -1 outside spin
        int displayExpiresAt = -1; // server tick; -1 while display should stay
        int lastResult = -1;
        int wheelIndex = 0;
        long phaseDeadline, nextAnimation;
        Display.TextDisplay display;
        RouletteTable(String block) { this.block=block; }
    }
    private static final class SlotSpin {
        final UUID player;
        final int wager;
        final int[] symbols;
        int ticks=0;
        SlotSpin(UUID player,int wager,int[] symbols){this.player=player;this.wager=wager;this.symbols=symbols;}
    }
    private static final Item[] SLOT_ITEMS = {Items.DIAMOND,Items.EMERALD,Items.GOLD_INGOT,Items.REDSTONE,Items.AMETHYST_SHARD,Items.NETHER_STAR};
    private static final String[] SLOT_LABELS = {"Diamonds","Emeralds","Gold","Redstone","Amethyst","Nether Star"};
    private GamesConfig games = new GamesConfig();
    private static final class GamesConfig {
        int minStake=1, maxStake=64, resultSeconds=5;
        String[] enabledGames=TYPES.clone();
        String language="nl_nl";
        int[] slotWeights={1,1,1,1,1,1};
        int[] slotThreePayout={12,9,7,5,4,25};
        int[] slotTwoPayout={2,1,1,1,1,2};
        int slotSpinTicks=30, slotAnimationTicks=3;
        int blackjackWinPayout=2, blackjackTiePayout=1, blackjackDealerStand=17;
        int higherLowerMinCorrect=2, higherLowerCorrectPerStep=2, higherLowerMaxPayout=32;
        int horseBettingSeconds=60, horseRaceTicks=720;
        int[] horseWeights=null, horsePayouts=null, horseArmorColors=null;
    }
    private static boolean validPayout(int n){return n>=1&&n<=100;}
    private static boolean validColor(int n){return n>=0&&n<=0xFFFFFF;}
    private static boolean validArray(int[] a,int length,int min,int max){return a!=null&&a.length==length&&Arrays.stream(a).allMatch(n->n>=min&&n<=max);}
    private static final Map<String,List<String>> CONFIG_GROUPS = Map.of(
        "general", List.of("minStake","maxStake","resultSeconds","enabledGames","language"),
        "slots", List.of("slotWeights","slotThreePayout","slotTwoPayout","slotSpinTicks","slotAnimationTicks"),
        "blackjack", List.of("blackjackWinPayout","blackjackTiePayout","blackjackDealerStand"),
        "higherlower", List.of("higherLowerMinCorrect","higherLowerCorrectPerStep","higherLowerMaxPayout"),
        "horse", List.of("horseBettingSeconds","horseRaceTicks","horseWeights","horsePayouts","horseArmorColors"));
    private Map<String,String> language = Map.of();
    private java.util.regex.Pattern languagePattern;
    private JsonObject readObject(Path path) throws java.io.IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    private JsonObject loadSplitConfig(Gson gson, Path legacy) throws java.io.IOException {
        JsonObject merged=gson.toJsonTree(new GamesConfig()).getAsJsonObject();
        if(Files.exists(legacy)) {
            for(var e:readObject(legacy).entrySet()) if(merged.has(e.getKey())) merged.add(e.getKey(),e.getValue());
        }
        for(var group:CONFIG_GROUPS.entrySet()) {
            Path path=legacy.resolveSibling(group.getKey()+".json");
            if(!Files.exists(path)) {
                JsonObject part=new JsonObject();
                for(String key:group.getValue()) part.add(key,merged.get(key));
                Files.writeString(path,gson.toJson(part));
            }
            JsonObject part=readObject(path);
            for(String key:group.getValue()) if(part.has(key)) merged.add(key,part.get(key));
        }
        return merged;
    }
    private Map<String,String> readLanguage(String code, Gson gson) throws java.io.IOException {
        if(code==null || !code.matches("[a-zA-Z0-9_-]{1,32}")) throw new IllegalArgumentException("invalid language name");
        Path dir=rouletteConfigPath().resolveSibling("lang"); Files.createDirectories(dir);
        JsonObject defaults;
        try(var stream=CasinoCraft.class.getResourceAsStream("/casinocraft/nl_nl.json")) {
            if(stream==null) throw new java.io.IOException("missing bundled language");
            defaults=JsonParser.parseString(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Path base=dir.resolve("nl_nl.json");
        if(!Files.exists(base)) Files.writeString(base,gson.toJson(defaults));
        Path selected=dir.resolve(code+".json");
        if(!Files.exists(selected)) Files.writeString(selected,gson.toJson(defaults));
        JsonObject custom=readObject(selected);
        Map<String,String> result=new HashMap<>();
        for(var e:custom.entrySet()) {
            if(e.getKey().isBlank() || !e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString()) throw new IllegalArgumentException("invalid language entry");
            String translated=e.getValue().getAsString();
            if(!e.getKey().equals(translated)) result.put(e.getKey(),translated);
        }
        return result;
    }
    private void setLanguage(Map<String,String> translations) {
        language=translations;
        languagePattern=translations.isEmpty()?null:java.util.regex.Pattern.compile(translations.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
            .map(key -> (Character.isLetterOrDigit(key.charAt(0))?"(?<![\\p{L}\\p{N}_])":"")+java.util.regex.Pattern.quote(key)+(Character.isLetterOrDigit(key.charAt(key.length()-1))?"(?![\\p{L}\\p{N}_])":""))
            .collect(java.util.stream.Collectors.joining("|")));
    }
    private String tr(String text) {
        if(text==null || languagePattern==null)return text;
        return languagePattern.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(language.get(m.group())));
    }
    private boolean loadGamesConfig() {
        Path path=rouletteConfigPath().resolveSibling("games.json");
        try {
            Files.createDirectories(path.getParent());
            Gson gson=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
            GamesConfig c=gson.fromJson(loadSplitConfig(gson,path),GamesConfig.class);
            if(c==null || c.minStake<1 || c.maxStake<c.minStake || c.maxStake>64 || c.resultSeconds<1 || c.resultSeconds>60 || c.enabledGames==null || !Arrays.asList(TYPES).containsAll(Arrays.asList(c.enabledGames)) ||
                !validArray(c.slotWeights,6,1,1000)||!validArray(c.slotThreePayout,6,0,100)||!validArray(c.slotTwoPayout,6,0,100)||c.slotSpinTicks<3||c.slotSpinTicks>1200||c.slotAnimationTicks<1||c.slotAnimationTicks>c.slotSpinTicks||
                !validPayout(c.blackjackWinPayout)||!validPayout(c.blackjackTiePayout)||c.blackjackDealerStand<1||c.blackjackDealerStand>21||c.higherLowerMinCorrect<2||c.higherLowerMinCorrect>100||c.higherLowerCorrectPerStep<1||c.higherLowerCorrectPerStep>100||!validPayout(c.higherLowerMaxPayout)||c.horseBettingSeconds<1||c.horseBettingSeconds>600||c.horseRaceTicks<20||c.horseRaceTicks>12000||
                (c.horseWeights!=null&&(!validArray(c.horseWeights,5,0,100)||Arrays.stream(c.horseWeights).sum()!=100))||(c.horsePayouts!=null&&!validArray(c.horsePayouts,5,1,100))||(c.horseArmorColors!=null&&!validArray(c.horseArmorColors,5,0,0xFFFFFF)))throw new IllegalArgumentException("invalid games config");
            Map<String,String> nextLanguage=readLanguage(c.language,gson);
            setLanguage(nextLanguage);
            games=c;
            if(c.horseWeights!=null)System.arraycopy(c.horseWeights,0,weights,0,5);
            if(c.horsePayouts!=null)System.arraycopy(c.horsePayouts,0,multipliers,0,5);
            if(c.horseArmorColors!=null)System.arraycopy(c.horseArmorColors,0,horseColors,0,5);
            return true;
        }catch(Exception e){System.err.println("[CasinoCraft] Invalid games config, keeping previous settings: "+e);return false;}
    }
    private int slotSymbol(){int n=random.nextInt(Arrays.stream(games.slotWeights).sum());for(int i=0;i<6;i++){n-=games.slotWeights[i];if(n<0)return i;}return 5;}
    private boolean enabled(String type){return Arrays.asList(games.enabledGames).contains(type);}
    private boolean stakeAllowed(int amount){return amount>=games.minStake&&amount<=games.maxStake;}
    private int reloadConfig(){
        if(server==null || !bets.isEmpty() || raceTicks>=0 || countdown>=0 || !slotSpins.isEmpty() || rouletteTables.values().stream().anyMatch(t->!t.bets.isEmpty()) || sessions.values().stream().anyMatch(s->s.rounds>0))return 0;
        GamesConfig previous=games; Map<String,String> previousLanguage=language; RouletteConfig previousRoulette=rouletteConfig; int[] oldWeights=weights.clone(),oldPayouts=multipliers.clone(),oldColors=horseColors.clone();
        if(!loadGamesConfig())return 0;
        if(!loadRouletteConfig()){games=previous;setLanguage(previousLanguage);rouletteConfig=previousRoulette;System.arraycopy(oldWeights,0,weights,0,5);System.arraycopy(oldPayouts,0,multipliers,0,5);System.arraycopy(oldColors,0,horseColors,0,5);return 0;}
        for(Session s:sessions.values())if(!stakeAllowed(s.stake))s.stake=games.minStake;
        for(CasinoMenu m:openMenus.values())m.refresh();
        return 1;
    }


    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("casinoplay")
                .then(Commands.argument("action", StringArgumentType.word())
                    .executes(ctx -> action(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx,"action")))));
            dispatcher.register(Commands.literal("casinocraft").requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)).then(Commands.literal("reload").executes(ctx -> reloadConfig())));
            dispatcher.register(Commands.literal("casino")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                .then(Commands.literal("reload").executes(ctx -> reloadConfig()))
                .then(Commands.literal("block")
                    .then(Commands.literal("set").then(Commands.argument("game", StringArgumentType.word())
                        .executes(ctx -> registerBlock(ctx.getSource().getPlayerOrException(),StringArgumentType.getString(ctx,"game")))))
                    .then(Commands.literal("remove").executes(ctx -> removeBlock(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("horse")
                    .then(Commands.literal("center").executes(ctx -> setCenter(ctx.getSource().getPlayerOrException())))
                    .then(Commands.literal("radiusx").then(Commands.argument("blocks",IntegerArgumentType.integer(1,1000))
                        .executes(ctx -> setTrackDimension(ctx.getSource().getPlayerOrException(), "x", IntegerArgumentType.getInteger(ctx,"blocks")))))
                    .then(Commands.literal("radiusz").then(Commands.argument("blocks",IntegerArgumentType.integer(1,1000))
                        .executes(ctx -> setTrackDimension(ctx.getSource().getPlayerOrException(), "z", IntegerArgumentType.getInteger(ctx,"blocks")))))
                    .then(Commands.literal("lanewidth").then(Commands.argument("blocks",IntegerArgumentType.integer(1,50))
                        .executes(ctx -> setTrackDimension(ctx.getSource().getPlayerOrException(), "lane", IntegerArgumentType.getInteger(ctx,"blocks")))))
                    .then(Commands.literal("info").executes(ctx -> trackInfo(ctx.getSource().getPlayerOrException())))
                    .then(Commands.literal("chance").then(Commands.argument("horse",IntegerArgumentType.integer(1,5))
                        .then(Commands.argument("percent",IntegerArgumentType.integer(0,100))
                            .executes(ctx -> setHorse(ctx.getSource().getPlayerOrException(),true,IntegerArgumentType.getInteger(ctx,"horse")-1,IntegerArgumentType.getInteger(ctx,"percent"))))))
                    .then(Commands.literal("payout").then(Commands.argument("horse",IntegerArgumentType.integer(1,5))
                        .then(Commands.argument("multiplier",IntegerArgumentType.integer(1,100))
                            .executes(ctx -> setHorse(ctx.getSource().getPlayerOrException(),false,IntegerArgumentType.getInteger(ctx,"horse")-1,IntegerArgumentType.getInteger(ctx,"multiplier"))))))
                    .then(Commands.literal("color").then(Commands.argument("horse",IntegerArgumentType.integer(1,5))
                        .then(Commands.argument("hex",StringArgumentType.word())
                            .executes(ctx -> setHorseColor(ctx.getSource().getPlayerOrException(),IntegerArgumentType.getInteger(ctx,"horse")-1,StringArgumentType.getString(ctx,"hex"))))))
                    .then(Commands.literal("start").executes(ctx -> forceRace(ctx.getSource().getPlayerOrException())))));
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            String pos = key(world.dimension().identifier().toString(),hit.getBlockPos());
            String type = blocks.get(pos);
            if (type==null) return InteractionResult.PASS;
            if(!enabled(type)){msg(sp,"Dit spel is uitgeschakeld.");return InteractionResult.SUCCESS;}
            Session prior=sessions.get(sp.getUUID());
            if(prior!=null&&prior.rounds>0){msg(sp,"Rond eerst je actieve kaartspel af.");if(prior.block.equals(pos))openCasinoGui(sp,prior);return InteractionResult.SUCCESS;}
            Session session = new Session(type,pos);
            session.stake=games.minStake;
            sessions.put(sp.getUUID(),session);
            openCasinoGui(sp,session);
            return InteractionResult.SUCCESS;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (server == null) { server=s; loadRouletteConfig(); load(); loadGamesConfig(); }
            tick++;
            if (tick % 20 == 0) second();
            if (raceTicks >= 0) stepRace();
            updateSlotSpins();
            updateResultScreens();
            updateRouletteTables();
            animateRouletteText();
            if (tick == 40 && !cleanedStartupDisplays) {
                cleanedStartupDisplays = true;
                for (var entry : blocks.entrySet())
                    if (entry.getValue().equals("roulette")) { RouletteTable t=rouletteTables.get(entry.getKey()); cleanupRouletteDisplays(entry.getKey(),t==null?null:t.display); }
            }
            if (tick % 20 == 0) {
                expireRouletteDisplays();
            }
            if (tick % 200 == 0 && dirty) save();
        });
    }

    /** Keep the result visible for five seconds before closing only that viewer's menu. */
    private void showFinalResult(ServerPlayer p, Session s, String title, String detail) {
        s.resultTitle = title;
        s.resultDetail = detail;
        s.resultExpiresAt = tick + games.resultSeconds*20;
        CasinoMenu menu = openMenus.get(p.getUUID());
        if (menu != null && menu.session == s) menu.refresh();
        msg(p, title + " - " + detail);
    }
    private void updateResultScreens() {
        for (var entry : new ArrayList<>(openMenus.entrySet())) {
            CasinoMenu menu = entry.getValue();
            Session session = menu.session;
            if (session.resultExpiresAt < 0) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (tick >= session.resultExpiresAt) {
                session.resultExpiresAt = -1;
                session.resultTitle = null;
                session.resultDetail = null;
                if (player != null && openMenus.get(entry.getKey()) == menu) player.closeContainer();
            } else if (tick % 20 == 0 && player != null) menu.refresh();
        }
    }
    private String key(String dim, BlockPos p) { return dim+"|"+p.getX()+","+p.getY()+","+p.getZ(); }
    private BlockPos targeted(ServerPlayer p) {
        var hit = p.pick(5,0,false);
        return hit instanceof net.minecraft.world.phys.BlockHitResult h ? h.getBlockPos() : null;
    }
    private String gameAt(ServerPlayer p, BlockPos b) { return key(p.level().dimension().identifier().toString(),b); }
    private int registerBlock(ServerPlayer p, String game) {
        if (!Arrays.asList(TYPES).contains(game)) { msg(p,"Spellen: "+String.join(", ",TYPES)); return 0; }
        BlockPos pos=targeted(p); if (pos==null) {msg(p,"Kijk naar een blok binnen 5 blokken.");return 0;}
        String blockKey=gameAt(p,pos);
        if(rouletteTables.containsKey(blockKey) && !rouletteTables.get(blockKey).bets.isEmpty()) {msg(p,"Wacht tot alle roulette-inzetten afgehandeld zijn.");return 0;}
        clearTable(blockKey);
        blocks.put(blockKey,game);dirty=true;msg(p,"Geregistreerd: "+game+" op "+pos.toShortString());return 1;
    }
    private int removeBlock(ServerPlayer p) {
        BlockPos pos=targeted(p); if(pos==null)return 0;
        String blockKey=gameAt(p,pos);
        if(rouletteTables.containsKey(blockKey)&&!rouletteTables.get(blockKey).bets.isEmpty()) {msg(p,"Roulette heeft actieve inzetten; verwijder na afloop.");return 0;}
        clearTable(blockKey);
        boolean removed=blocks.remove(blockKey)!=null;dirty|=removed;msg(p,removed?"Casinoblok verwijderd.":"Geen casinoblok hier.");return removed?1:0;
    }
    private int setCenter(ServerPlayer p) {
        if (!canEditTrack(p)) return 0;
        trackCenter=p.blockPosition();trackDimension=p.level().dimension().identifier().toString();dirty=true;
        msg(p,"Midden renbaan ingesteld op "+trackCenter.toShortString()+". Bouw een ovaal met 5 banen rondom dit punt.");return 1;
    }
    private boolean canEditTrack(ServerPlayer p) {
        if (raceTicks >= 0 || countdown >= 0 || !bets.isEmpty()) {
            msg(p,"De renbaan kan niet worden aangepast terwijl een race of inzetronde actief is.");
            return false;
        }
        return true;
    }
    private boolean validTrack(double rx, double rz, double spacing) {
        return rx >= 4.0*spacing+3.0 && rz >= 4.0*spacing+3.0;
    }
    private int setTrackDimension(ServerPlayer p, String which, int blocks) {
        if (!canEditTrack(p)) return 0;
        double rx = which.equals("x") ? blocks : trackRadiusX;
        double rz = which.equals("z") ? blocks : trackRadiusZ;
        double width = which.equals("lane") ? blocks : laneWidth;
        if (!validTrack(rx,rz,width)) {
            msg(p,"Ongeldige afmetingen: beide buitenste stralen moeten minimaal (4 x baanbreedte + 3) zijn.");
            return 0;
        }
        trackRadiusX=rx;trackRadiusZ=rz;laneWidth=width;dirty=true;
        return trackInfo(p);
    }
    private int trackInfo(ServerPlayer p) {
        msg(p,"Renbaan: buitenste route circa "+(int)(2*trackRadiusX+1)+" x "+(int)(2*trackRadiusZ+1)+
            " blokken, tussenafstand routes "+(int)laneWidth+" blokken; binnenste stralen " +
            (trackRadiusX-4*laneWidth)+" / "+(trackRadiusZ-4*laneWidth)+".");
        return 1;
    }
    private int setHorse(ServerPlayer p, boolean chance, int horse, int value) {
        if(chance) {
            int sum=value;for(int i=0;i<5;i++)if(i!=horse)sum+=weights[i];
            // Allow sequential edits, but prohibit starting a race until the total is 100.
            weights[horse]=value;msg(p,"Kans "+NAMES[horse]+" = "+value+"%. Totaal: "+sum+"% (moet 100% zijn voor een race).");
        } else {multipliers[horse]=value;msg(p,"Uitbetaling "+NAMES[horse]+" = "+value+"x.");}
        dirty=true;return 1;
    }
    private int setHorseColor(ServerPlayer p, int horse, String input) {
        String hex = input.startsWith("#") ? input.substring(1) : input;
        if(!hex.matches("(?i)[0-9a-f]{6}")) {
            msg(p,"Gebruik een RGB-kleur van zes hextekens, bijvoorbeeld FF0000 (rood).");
            return 0;
        }
        horseColors[horse]=Integer.parseInt(hex,16);
        dirty=true;
        msg(p,"Paard "+(horse+1)+" ("+NAMES[horse]+") krijgt lederen paardenpantser met kleur #"+hex.toUpperCase(Locale.ROOT)+".");
        return 1;
    }
    private String colorHex(int horse){return String.format(Locale.ROOT,"#%06X",horseColors[horse]);}
    private int forceRace(ServerPlayer p) {
        if(raceTicks>=0) {msg(p,"Race loopt al.");return 0;}
        if (bets.isEmpty()) {msg(p,"Geen inzetten geplaatst.");return 0;}
        if (!validChances()) {msg(p,"Kansen moeten samen 100% zijn.");return 0;}
        countdown=1;msg(p,"Race start over 1 seconde.");return 1;
    }
    private boolean validChances(){return Arrays.stream(weights).sum()==100;}
    private void msg(ServerPlayer p,String text){p.sendSystemMessage(Component.literal("[CasinoCraft] "+tr(text)));}
    private void link(ServerPlayer p,String title,String action) {
        p.sendSystemMessage(Component.literal("[ "+tr(title)+" ]").withStyle(s ->
            s.withColor(0xF5C842).withClickEvent(new ClickEvent.RunCommand("/casinoplay "+action))));
    }
    private void horseLink(ServerPlayer p,int i) {
        String description="Paard "+(i+1)+": "+NAMES[i]+" ["+colorHex(i)+"] | "+weights[i]+"% winstkans | "+multipliers[i]+"x";
        p.sendSystemMessage(Component.literal("[ "+tr(description)+" ]").withStyle(style ->
            style.withColor(horseColors[i]).withClickEvent(new ClickEvent.RunCommand("/casinoplay horse"+(i+1)))));
    }
    private boolean available(ServerPlayer p,Session s) {
        if(s==null||!enabled(s.type))return false;
        String stored=blocks.get(s.block);
        if(!s.type.equals(stored))return false;
        String[] parts=s.block.split("\\|");
        if(parts.length!=2 || !parts[0].equals(p.level().dimension().identifier().toString()))return false;
        String[] coords=parts[1].split(",");
        BlockPos block=new BlockPos(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]),Integer.parseInt(coords[2]));
        return p.blockPosition().distSqr(block)<=64;
    }
    private void show(ServerPlayer p, Session s) {
        msg(p,"=== "+s.type.toUpperCase(Locale.ROOT)+" | inzet: "+s.stake+" diamond(s) ===");
        if(s.type.equals("horse")) {
            for(int i=0;i<5;i++)horseLink(p,i);
            msg(p,"Gekozen: "+NAMES[s.selected]+". Nieuwe inzetten sluiten bij de start.");
        } else if(s.type.equals("blackjack") && s.rounds>0){msg(p,"Jouw score: "+s.value+" | dealer zichtbaar: "+s.dealer);link(p,"Hit","hit");link(p,"Stand","stand");return;
        } else if(s.type.equals("higherlower") && s.rounds>0){msg(p,"Huidige kaart: "+s.value+" | juist: "+(s.rounds-1)+" | prijs: "+higherLowerMultiplier(s)+"x");link(p,"Hoger","higher");link(p,"Lager","lower");if(s.rounds-1>=games.higherLowerMinCorrect)link(p,"Innemen","cashout");else msg(p,"Innemen pas mogelijk na "+games.higherLowerMinCorrect+" juiste voorspellingen (nog "+(games.higherLowerMinCorrect-(s.rounds-1))+" nodig).");return;}
        else if(s.type.equals("roulette")){
            showRoulette(p,s);
        }
        link(p,"Inzet 1","bet1");link(p,"Inzet 5","bet5");link(p,"Inzet 10","bet10");link(p,"Inzet 32","bet32");link(p,"Inzet 64","bet64");
        if(s.type.equals("horse"))link(p,"Bevestig inzet op "+NAMES[s.selected],"place");
        else if(s.type.equals("slots"))link(p,"Draai slots","spin");
        else if(s.type.equals("blackjack"))link(p,"Deel kaarten","deal");
        else if(s.type.equals("higherlower"))link(p,"Start hoger/lager","deal");
    }
    private int action(ServerPlayer p,String a) {
        Session s=sessions.get(p.getUUID());
        if(!available(p,s)){msg(p,"Klik eerst op een geregistreerd casinoblok en blijf erbij.");return 0;}
        if(a.startsWith("bet") && s.rounds==0 && !slotSpins.containsKey(p.getUUID())) {
            try {int n=Integer.parseInt(a.substring(3)); if(!stakeAllowed(n))return 0;s.stake=n;show(p,s);return 1;} catch(NumberFormatException ignored){return 0;}
        }
        if(s.type.equals("horse")) {
            if(a.matches("horse[1-5]")){s.selected=Integer.parseInt(a.substring(5))-1;show(p,s);return 1;}
            if(a.equals("place"))return placeBet(p,s);
        }
        if(s.type.equals("slots") && a.equals("spin"))return slots(p,s);
        if(s.type.equals("roulette")) {
            if(a.equals("rmain") || a.equals("rdozens") || a.equals("rcolumns") || a.equals("rnumbers1") || a.equals("rnumbers2") || a.equals("rnumbers3")) {
                s.roulettePage=a.substring(1);show(p,s);return 1;
            }
            if(a.equals("rspin")){msg(p,"Roulette draait automatisch na de inzetperiode.");return 1;}
            if(a.equals("rclear")) { s.rouletteChips.clear(); show(p,s); return 1; }
            if(a.equals("rundo")) {
                if(!s.rouletteChips.isEmpty())s.rouletteChips.remove(s.rouletteChips.size()-1);
                show(p,s);return 1;
            }
            if(isRouletteChoice(a)){placeTableBet(p,s,a);return 1;}
        }
        if(s.type.equals("blackjack"))return blackjack(p,s,a);
        if(s.type.equals("higherlower"))return higherlower(p,s,a);
        return 0;
    }
    // Inventory operations run on the main server thread. No rewards are paid on a failed debit.
    private boolean debit(ServerPlayer p,int amount) {
        int count=0;
        for(int i=0;i<p.getInventory().getContainerSize();i++){
            ItemStack st=p.getInventory().getItem(i);if(st.is(Items.DIAMOND))count+=st.getCount();
        }
        if(count<amount){msg(p,"Niet genoeg diamonds. Nodig: "+amount);return false;}
        int remaining=amount;
        for(int i=0;i<p.getInventory().getContainerSize()&&remaining>0;i++){
            ItemStack st=p.getInventory().getItem(i);
            if(st.is(Items.DIAMOND)){int take=Math.min(st.getCount(),remaining);st.shrink(take);remaining-=take;}
        }
        p.getInventory().setChanged();return true;
    }
    private void credit(UUID id,long amount) {
        if(amount<=0)return;
        unpaid.merge(id,amount,Long::sum);dirty=true;
        deliver(id);
    }
    private void deliver(UUID id){
        if(server==null)return;
        ServerPlayer p=server.getPlayerList().getPlayer(id);
        if(p==null)return;
        long amount=unpaid.getOrDefault(id,0L);if(amount<=0)return;
        // Pay only items we can insert; excess remains pending for a future interaction.
        while(amount>0){
            int count=(int)Math.min(64,amount);
            ItemStack stack=new ItemStack(Items.DIAMOND,count);
            if(!p.getInventory().add(stack))break;
            amount-=count;
        }
        if(amount==0)unpaid.remove(id);else unpaid.put(id,amount);
        dirty=true;
    }
    private int placeBet(ServerPlayer p,Session s){
        if(!stakeAllowed(s.stake)){msg(p,"Inzet buiten toegestane limieten.");return 0;}
        if(raceTicks>=0){msg(p,"Race is bezig. Wacht op de volgende.");return 0;}
        if(trackCenter==null){msg(p,"Admin moet eerst /casino horse center instellen.");return 0;}
        if(!validChances()){msg(p,"Admin moet eerst kansen instellen tot 100%.");return 0;}
        if(!stakeAllowed(s.stake)||!debit(p,s.stake))return 0;
        bets.add(new Bet(p.getUUID(),s.selected,s.stake,multipliers[s.selected]));
        if(countdown<0)countdown=games.horseBettingSeconds;
        dirty=true;msg(p,"Inzet bevestigd: "+s.stake+" diamonds op "+NAMES[s.selected]+". Start over "+countdown+"s.");return 1;
    }
    private int slots(ServerPlayer p,Session s){
        return startSlotSpin(p,s) ? 1 : 0;
    }
    /** Vanilla clickable chat choices, accessible only after using a registered casino block. */
    private void showRoulette(ServerPlayer p,Session s) {
        switch(s.roulettePage) {
            case "dozens" -> {
                link(p,"1e dozijn: 1-12 ("+rouletteConfig.groupPayout+"x)","rdozen1");
                link(p,"2e dozijn: 13-24 ("+rouletteConfig.groupPayout+"x)","rdozen2");
                link(p,"3e dozijn: 25-36 ("+rouletteConfig.groupPayout+"x)","rdozen3");
            }
            case "columns" -> {
                link(p,"Kolom 1: 1,4,7,...,34 ("+rouletteConfig.groupPayout+"x)","rcolumn1");
                link(p,"Kolom 2: 2,5,8,...,35 ("+rouletteConfig.groupPayout+"x)","rcolumn2");
                link(p,"Kolom 3: 3,6,9,...,36 ("+rouletteConfig.groupPayout+"x)","rcolumn3");
            }
            case "numbers1", "numbers2", "numbers3" -> {
                int page=Integer.parseInt(s.roulettePage.substring(7));
                int from=page==1?0:page==2?13:25;
                int to=page==1?12:page==2?24:36;
                msg(p,"Kies een nummer (0-36). Uitbetaling: "+rouletteConfig.numberPayout+"x; nul: "+rouletteConfig.zeroPayout+"x inclusief inzet.");
                for(int n=from;n<=to;n++)link(p,"Nummer "+n+" ("+rouletteColor(n)+", "+(n==0?rouletteConfig.zeroPayout:rouletteConfig.numberPayout)+"x)","rn"+n);
                link(p,"Nummers 0-12","rnumbers1");
                link(p,"Nummers 13-24","rnumbers2");
                link(p,"Nummers 25-36","rnumbers3");
            }
            default -> {
                link(p,"Rood ("+rouletteConfig.outsidePayout+"x)","rred");link(p,"Zwart ("+rouletteConfig.outsidePayout+"x)","rblack");
                link(p,"Even ("+rouletteConfig.outsidePayout+"x)","reven");link(p,"Oneven ("+rouletteConfig.outsidePayout+"x)","rodd");
                link(p,"Laag: 1-18 ("+rouletteConfig.outsidePayout+"x)","rlow");link(p,"Hoog: 19-36 ("+rouletteConfig.outsidePayout+"x)","rhigh");
                link(p,"Nul / groen ("+rouletteConfig.zeroPayout+"x)","rn0");
                link(p,"Drie dozijnen ("+rouletteConfig.groupPayout+"x)","rdozens");
                link(p,"Drie kolommen ("+rouletteConfig.groupPayout+"x)","rcolumns");
                link(p,"Afzonderlijke nummers ("+rouletteConfig.numberPayout+"x)","rnumbers1");
            }
        }
        if(!s.roulettePage.equals("main"))link(p,"Terug naar roulette-overzicht","rmain");
        int total=rouletteTotal(s);
        msg(p,"Roulettekaart: "+s.rouletteChips.size()+" inzet(ten), totaal "+total+" diamonds (max "+rouletteConfig.maxTotal+").");
        for(RouletteChip chip:s.rouletteChips)msg(p," • "+rouletteLabel(chip.choice())+": "+chip.amount()+" diamond(s)");
        msg(p,"Kies een inzetvak om "+s.stake+" diamond(s) toe te voegen. Alles draait samen op één nummer.");
        link(p,"DRAAI ROULETTE ("+total+" diamonds)","rspin");
        link(p,"Laatste inzet verwijderen","rundo");
        link(p,"Alle inzetten wissen","rclear");
    }
    private int rouletteWinner() {
        int n=random.nextInt(36+rouletteConfig.zeroWeight);
        return n<rouletteConfig.zeroWeight?0:n-rouletteConfig.zeroWeight+1;
    }
    private int rouletteRgb(int n) { return n==0?rouletteConfig.greenColor:RED_NUMBERS.contains(n)?rouletteConfig.redColor:rouletteConfig.blackColor; }
    private String rouletteColor(int n) {
        return n==0?"groen":RED_NUMBERS.contains(n)?"rood":"zwart";
    }
    private boolean isRouletteChoice(String a) {
        if(Set.of("rred","rblack","reven","rodd","rlow","rhigh",
                "rdozen1","rdozen2","rdozen3","rcolumn1","rcolumn2","rcolumn3").contains(a))return true;
        if(!a.matches("rn(?:[0-9]|[12][0-9]|3[0-6])"))return false;
        return true;
    }
    // Return the total payout, including the original stake. Zero loses on all outside bets.
    private int rouletteMultiplier(String choice,int n) {
        if(choice.startsWith("rn") && choice.substring(2).matches("[0-9]+"))
            return n==Integer.parseInt(choice.substring(2))?(n==0?rouletteConfig.zeroPayout:rouletteConfig.numberPayout):0;
        if(n==0)return 0;
        return switch(choice) {
            case "rred" -> RED_NUMBERS.contains(n)?rouletteConfig.outsidePayout:0;
            case "rblack" -> !RED_NUMBERS.contains(n)?rouletteConfig.outsidePayout:0;
            case "reven" -> n%2==0?rouletteConfig.outsidePayout:0;
            case "rodd" -> n%2!=0?rouletteConfig.outsidePayout:0;
            case "rlow" -> n<=18?rouletteConfig.outsidePayout:0;
            case "rhigh" -> n>=19?rouletteConfig.outsidePayout:0;
            case "rdozen1","rdozen2","rdozen3" -> (n-1)/12==Integer.parseInt(choice.substring(6))-1?rouletteConfig.groupPayout:0;
            case "rcolumn1","rcolumn2","rcolumn3" -> (n-1)%3==Integer.parseInt(choice.substring(7))-1?rouletteConfig.groupPayout:0;
            default -> 0;
        };
    }
    private String rouletteLabel(String choice) {
        if(choice.startsWith("rn") && choice.substring(2).matches("[0-9]+"))return "Nummer "+choice.substring(2);
        return switch(choice) {
            case "rred" -> "Rood"; case "rblack" -> "Zwart";
            case "reven" -> "Even"; case "rodd" -> "Oneven";
            case "rlow" -> "1-18"; case "rhigh" -> "19-36";
            case "rdozen1" -> "1e dozijn"; case "rdozen2" -> "2e dozijn";
            case "rdozen3" -> "3e dozijn"; case "rcolumn1" -> "1e kolom";
            case "rcolumn2" -> "2e kolom"; case "rcolumn3" -> "3e kolom";
            default -> choice;
        };
    }
    private int rouletteTotal(Session s) {
        int total=0;
        for(RouletteChip chip:s.rouletteChips)total+=chip.amount();
        return total;
    }
    // Selecting a betting field only queues a chip: inventory is debited once at spin time.
    private int addRouletteChip(ServerPlayer p, Session s, String choice) {
        if(!isRouletteChoice(choice))return 0;
        if(s.rouletteChips.size()>=rouletteConfig.maxChips || rouletteTotal(s)+s.stake>rouletteConfig.maxTotal){
            msg(p,"Maximaal "+rouletteConfig.maxChips+" inzetten en "+rouletteConfig.maxTotal+" diamonds per draai.");return 0;
        }
        s.rouletteChips.add(new RouletteChip(choice,s.stake));
        msg(p,"Toegevoegd: "+s.stake+" diamonds op "+rouletteLabel(choice)+".");
        show(p,s);return 1;
    }
    private int spinRoulette(ServerPlayer p, Session s) {
        int total=rouletteTotal(s);
        if(total<=0){msg(p,"Kies eerst minstens één inzetvak.");return 0;}
        if(!debit(p,total))return 0;
        int number=rouletteWinner();
        long payout=0;
        for(RouletteChip chip:s.rouletteChips) {
            long earned=(long)chip.amount()*rouletteMultiplier(chip.choice(),number);
            payout+=earned;
            msg(p,rouletteLabel(chip.choice())+": "+(earned>0?"+"+earned+" diamonds":"verloren"));
        }
        s.rouletteChips.clear();
        msg(p,"Roulette: "+number+" ("+rouletteColor(number)+"). Inzet: "+total+" | Uitbetaling: "+payout+" | Netto: "+(payout-total)+" diamonds.");
        credit(p.getUUID(),payout);
        return 1;
    }
    private int blackjack(ServerPlayer p,Session s,String a){
        if (s.resultExpiresAt >= 0) return 0;
        if(a.equals("deal")&&s.rounds==0){
            if(!stakeAllowed(s.stake)||!debit(p,s.stake))return 0;
            s.value=card()+card();s.dealer=card();s.rounds=1;show(p,s);return 1;
        }
        if(s.rounds==0)return 0;
        if(a.equals("hit")){
            s.value+=card();
            if(s.value>21){
                s.rounds=0;
                showFinalResult(p,s,"BUST - VERLOREN", "Je hebt "+s.value+" punten, meer dan 21. Verlies: "+s.stake+" diamonds.");
            }else show(p,s);
            return 1;
        }
        if(a.equals("stand")){
            int dealer=s.dealer+card();while(dealer<games.blackjackDealerStand)dealer+=card();
            int mult=(dealer>21||s.value>dealer)?games.blackjackWinPayout:(s.value==dealer?games.blackjackTiePayout:0);
            long earned=(long)s.stake*mult;
            credit(p.getUUID(),earned);
            s.rounds=0;
            String reason = dealer>21 ? "Dealer is bust ("+dealer+")." :
                s.value>dealer ? "Jij hebt "+s.value+", dealer "+dealer+"." :
                s.value==dealer ? "Gelijkspel: beide "+s.value+"." :
                "Dealer heeft "+dealer+" en jij "+s.value+".";
            showFinalResult(p,s,mult==0?"VERLOREN":mult==1?"GELIJKSPEL":"GEWONNEN",
                reason+" Uitbetaling: "+earned+" diamonds.");
            return 1;
        }return 0;
    }
    private int card(){return Math.min(10,random.nextInt(13)+1);}
    // The opening card does not count as a correct prediction.
    // 0-1 correct: 1x, 2-3 correct: 2x, 4-5 correct: 3x, etc.
    private int higherLowerMultiplier(Session session) {
        int correctGuesses = Math.max(0, session.rounds - 1);
        return Math.min(games.higherLowerMaxPayout, 1 + correctGuesses / games.higherLowerCorrectPerStep);
    }
    private int higherlower(ServerPlayer p,Session s,String a){
        if (s.resultExpiresAt >= 0) return 0;
        if(a.equals("deal")&&s.rounds==0){if(!stakeAllowed(s.stake)||!debit(p,s.stake))return 0;s.value=random.nextInt(13)+1;s.rounds=1;show(p,s);return 1;}
        if(s.rounds==0)return 0;
        if(a.equals("cashout")){
            // Never allow early payouts, including direct or stale menu clicks.
            if (s.rounds - 1 < games.higherLowerMinCorrect) {
                msg(p,"Je moet eerst "+games.higherLowerMinCorrect+" keer juist raden voordat je diamonds kunt innen.");
                return 0;
            }
            long prize=(long)s.stake*higherLowerMultiplier(s);
            credit(p.getUUID(),prize);s.rounds=0;
            showFinalResult(p,s,"UITBETAALD", "Je hebt op tijd gestopt en "+prize+" diamonds ontvangen.");
            return 1;
        }
        if(a.equals("higher")||a.equals("lower")){
            int previous=s.value, next=random.nextInt(13)+1;
            boolean win=a.equals("higher")?next>previous:next<previous;
            if(win){s.rounds++;s.value=next;show(p,s);}
            else {
                s.rounds=0;
                String why = next==previous ? "De volgende kaart was gelijk ("+next+")." :
                    "Je koos "+(a.equals("higher")?"HOGER":"LAGER")+", maar de volgende kaart was "+
                    (next<previous?"LAGER":"HOGER")+" ("+previous+" naar "+next+").";
                showFinalResult(p,s,"VERLOREN", why+" Verlies: "+s.stake+" diamonds.");
            }
            return 1;
        }return 0;
    }
    private void second(){
        if(server==null)return;
        for(UUID id:new ArrayList<>(unpaid.keySet()))deliver(id);
        if(countdown>0){countdown--;if(countdown%15==0 || countdown<=5)notifyHorsePlayers("Paardenrace start over "+countdown+" seconden!");}
        if(countdown==0){countdown=-1;startRace();}
        for(CasinoMenu menu:new ArrayList<>(openMenus.values()))
            if(menu.session.type.equals("horse"))menu.refresh();
    }
    // No global chat: only players who bought into this particular race are notified.
    private void notifyHorsePlayers(String text) {
        if (server == null) return;
        Set<UUID> participants = new HashSet<>();
        for (Bet bet : bets) participants.add(bet.player());
        for (UUID id : participants) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) msg(player, text);
        }
    }
    private void prepareOvalPaths() {
        for (int lane = 0; lane < 5; lane++) {
            laneDistance[lane][0] = 0;
            double[] before = oval(lane, 0);
            for (int step = 1; step <= RACE_PATH_STEPS; step++) {
                double[] now = oval(lane, (double) step / RACE_PATH_STEPS);
                laneDistance[lane][step] = laneDistance[lane][step-1] +
                    Math.hypot(now[0] - before[0], now[1] - before[1]);
                before = now;
            }
            laneLength[lane] = laneDistance[lane][RACE_PATH_STEPS];
        }
    }
    // Return an angle progress corresponding to a fraction of true path length.
    private double evenDistanceProgress(int lane, double completed) {
        if (completed <= 0) return 0;
        if (completed >= 1) return 1;
        double target = completed * laneLength[lane];
        int lo = 0, hi = RACE_PATH_STEPS;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (laneDistance[lane][mid] < target) lo = mid;
            else hi = mid;
        }
        double span = laneDistance[lane][hi] - laneDistance[lane][lo];
        double offset = span > 0 ? (target - laneDistance[lane][lo]) / span : 0;
        return (lo + offset) / RACE_PATH_STEPS;
    }
    // Natural Minecraft yaw: face the tangent of the route rather than sideways.
    private void orientHorse(Horse horse, int lane, double progress) {
        double a = evenDistanceProgress(lane, progress);
        double b = evenDistanceProgress(lane, Math.min(1,progress + 0.002));
        if (b == a) { a = evenDistanceProgress(lane,Math.max(0,progress - 0.002)); b = 1; }
        double[] from = oval(lane,a), to = oval(lane,b);
        float yaw = (float)Math.toDegrees(Math.atan2(-(to[0]-from[0]),to[1]-from[1]));
        horse.setYRot(yaw);
        horse.setYHeadRot(yaw);
        horse.setYBodyRot(yaw);
    }
    private void startRace(){
        if(bets.isEmpty()||trackCenter==null||!validChances())return;
        ServerLevel level=server.getAllLevels().iterator().next();
        for(ServerLevel candidate:server.getAllLevels())if(candidate.dimension().identifier().toString().equals(trackDimension)){level=candidate;break;}
        runners.clear();Arrays.fill(raceProgress,0);prepareOvalPaths();
        chosenWinner=weightedWinner();raceTicks=0;
        for(int i=0;i<5;i++){
            Horse horse=EntityTypes.HORSE.create(level,EntitySpawnReason.COMMAND);
            if(horse!=null){horse.setNoAi(true);horse.setNoGravity(true);horse.setPermanentlyInvulnerable(true);
                ItemStack coloredArmor = new ItemStack(Items.LEATHER_HORSE_ARMOR);
                coloredArmor.set(DataComponents.DYED_COLOR, new DyedItemColor(horseColors[i]));
                horse.setItemSlot(EquipmentSlot.BODY, coloredArmor);
                horse.setCustomName(Component.literal(tr(NAMES[i])).withColor(horseColors[i]));horse.setCustomNameVisible(true);
                double[] xy=oval(i,0);horse.setPos(xy[0],horseGroundY(level,xy[0],xy[1]),xy[1]);orientHorse(horse,i,0);level.addFreshEntity(horse);runners.add(horse);}
        }
        notifyHorsePlayers("🐎 Race begonnen! Vijf paarden zijn vertrokken.");dirty=true;
    }
    /**
     * Resolve the foot height close to the configured track plane. Look ONLY at
     * floor blocks, not walls/obstacles at the horse's head or chest height.
     * The centre command should be used while standing on the track surface.
     * The horse is positioned directly, so it can pass through route obstructions.
     */
    private double horseGroundY(ServerLevel level, double x, double z) {
        int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
        int base = trackCenter.getY();
        // Prefer the configured surface and small downward ground variations.
        for (int y = base - 1; y >= base - 4; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double top = y + shape.max(Direction.Axis.Y);
                if (top <= base + 0.05) return top;
            }
        }
        // A gap in the track: stay at track level rather than fall into it.
        return base;
    }

    private int weightedWinner(){int n=random.nextInt(100);for(int i=0;i<5;i++){n-=weights[i];if(n<0)return i;}return 4;}
    // Five concentric oval lanes. rx/ry are block offsets from the admin-selected center.
    private double[] oval(int lane,double progress){
        // Lane 0 = innermost; lane 4 = outermost. Both adapt to admin settings.
        double rx=trackRadiusX-(4-lane)*laneWidth,rz=trackRadiusZ-(4-lane)*laneWidth;
        double angle=progress*2*Math.PI-Math.PI/2;
        return new double[]{trackCenter.getX()+0.5+rx*Math.cos(angle),trackCenter.getZ()+0.5+rz*Math.sin(angle)};
    }
    private void stepRace(){
        raceTicks++;
        // All five horses traverse their full oval with consistent distance interpolation.
        // The weighted winner arrives first, while the others remain in a close race.
        for (int i = 0; i < 5; i++) {
            int finishTicks = i == chosenWinner ? games.horseRaceTicks : games.horseRaceTicks + 35 + i * 13;
            double progress = Math.min(1.0, (double)raceTicks / finishTicks);
            raceProgress[i] = progress;
            if (i < runners.size()) {
                Horse horse = runners.get(i);
                if (horse.isAlive()) {
                    double pathProgress = evenDistanceProgress(i,progress);
                    double[] pos = oval(i,pathProgress);
                    ServerLevel horseLevel = (ServerLevel)horse.level();
                    double groundY = horseGroundY(horseLevel,pos[0],pos[1]);
                    // Absolute server position bypasses obstacles in the route.
                    // Zero velocity + no gravity stops the entity drifting/falling
                    // between ticks and avoids normal movement-based shoving.
                    horse.setDeltaMovement(Vec3.ZERO);
                    horse.setPos(pos[0],groundY,pos[1]);
                    orientHorse(horse,i,progress);
                }
            }
        }
        if (raceProgress[chosenWinner] >= 1) finishRace();
    }
    private void finishRace(){
        raceTicks=-1;
        notifyHorsePlayers("🏆 Winnaar: "+NAMES[chosenWinner]+"! Uitbetaling: "+multipliers[chosenWinner]+"x.");
        // Persist pending payouts before attempting delivery. Actual crash-safe ledger requires additional testing.
        for(Bet bet:bets)if(bet.horse==chosenWinner)credit(bet.player,(long)bet.stake*bet.multiplier);
        bets.clear();for(Horse horse:runners)if(horse.isAlive())horse.discard();runners.clear();
        chosenWinner=-1;dirty=true;save();
    }
    // Vanilla chest screen; no custom client packet or resource pack is required.
    private void openCasinoGui(ServerPlayer p, Session s) {
        p.openMenu(new SimpleMenuProvider((id, inv, who) -> {
            CasinoMenu menu=new CasinoMenu(id,inv,s,p.getUUID());
            openMenus.put(p.getUUID(),menu);
            menu.refresh();
            return menu;
        }, Component.literal("CasinoCraft - "+tr(switch(s.type){case "horse" -> "Paardenrace"; case "blackjack" -> "Blackjack"; case "higherlower" -> "Hoger of Lager"; case "roulette" -> "Roulette"; default -> "Slots";}))));
    }
    private final class CasinoMenu extends ChestMenu {
        final Session session;
        final UUID viewer;
        final SimpleContainer virtual;
        final Map<Integer,String> actions=new HashMap<>();
        int page=0;
        CasinoMenu(int id, Inventory inv, Session session, UUID viewer) {
            this(id,inv,session,viewer,new SimpleContainer(54));
        }
        private CasinoMenu(int id,Inventory inv,Session session,UUID viewer,SimpleContainer inventory) {
            super(MenuType.GENERIC_9x6,id,inv,inventory,6);
            this.session=session;this.viewer=viewer;this.virtual=inventory;
        }
        void button(int slot,Item item,String title,String action){
            if(action.matches("bet[0-9]+")){int amount=Integer.parseInt(action.substring(3)); if(amount==1)amount=games.minStake;else if(amount==64)amount=games.maxStake; if(!stakeAllowed(amount))return; title="Inzet: "+amount+" diamonds";action="bet"+amount;}
            buttonStack(slot,new ItemStack(item),title,action);
        }
        void buttonStack(int slot,ItemStack stack,String title,String action){
            actions.put(slot,action);
            stack.set(DataComponents.CUSTOM_NAME,Component.literal(tr(title)));
            virtual.setItem(slot,stack);
        }
        void refresh() {
            actions.clear();virtual.clearContent();
            if(session.resultExpiresAt >= 0) {
                boolean won = session.resultTitle != null &&
                    (session.resultTitle.contains("GEWONNEN") || session.resultTitle.contains("UITBETAALD") || session.resultTitle.contains("UITSLAG"));
                button(4,Items.BOOK,session.resultTitle == null ? "UITSLAG" : session.resultTitle,"noop");
                button(22,won?Items.DIAMOND:Blocks.CONCRETE.red().asItem(),
                    session.resultDetail == null ? "Uitslag wordt verwerkt" : session.resultDetail,"noop");
                button(40,Items.CLOCK,"Venster sluit over "+Math.max(1,(session.resultExpiresAt-tick+19)/20)+" seconden","noop");
                button(49,Items.BARRIER,"SLUIT RESULTAAT","closeResult");
                broadcastChanges();
                return;
            }
            if(session.type.equals("slots")) {
                SlotSpin activeSpin = slotSpins.get(viewer);
                button(10,Items.PAPER,"3 gelijk: Diamond "+games.slotThreePayout[0]+"x | Emerald "+games.slotThreePayout[1]+"x | Gold "+games.slotThreePayout[2]+"x","noop");
                button(11,Items.PAPER,"Redstone "+games.slotThreePayout[3]+"x | Amethyst "+games.slotThreePayout[4]+"x | Nether Star "+games.slotThreePayout[5]+"x","noop");
                button(12,Items.PAPER,"2 gelijk (Diamond, Emerald, Gold, Redstone, Amethyst, Star): "+Arrays.toString(games.slotTwoPayout)+"x","noop");
                // Keep the last THREE actual outcomes visible after a spin until the next spin starts.
                int[] reel = activeSpin != null ? new int[]{random.nextInt(6),random.nextInt(6),random.nextInt(6)} :
                    (session.lastSlotSymbols != null ? session.lastSlotSymbols : new int[]{0,2,1});
                for(int i=0;i<3;i++)button(21+i,SLOT_ITEMS[reel[i]],"Rol "+(i+1)+": "+SLOT_LABELS[reel[i]],"noop");
                if (activeSpin != null) {
                    button(31,Items.CLOCK,"ROLLEN DRAAIEN...","noop");
                    button(49,Items.CLOCK,"WACHT TOT DE ROLLEN STOPPEN","noop");
                } else {
                    if (session.lastSlotTitle != null) {
                        button(30,Items.BOOK,session.lastSlotTitle,"noop");
                        button(31,session.lastSlotDetail != null && session.lastSlotDetail.startsWith("Uitbetaling:")
                            ? Items.DIAMOND : Items.BARRIER,
                            session.lastSlotDetail == null ? "" : session.lastSlotDetail,"noop");
                    } else {
                        button(31,Items.BOOK,"Kies je inzet en draai!","noop");
                    }
                    button(39,Blocks.WOOL.red().asItem(),"Inzet: 1 diamond","bet1");
                    button(40,Items.GOLD_INGOT,"Inzet: 5 diamonds","bet5");
                    button(41,Items.DIAMOND,"Inzet: 10 diamonds","bet10");
                    button(42,Items.DIAMOND_BLOCK,"Inzet: 32 diamonds","bet32");
                    button(43,Items.EMERALD_BLOCK,"Inzet: 64 diamonds","bet64");
                    button(49,Items.LEVER,"DRAAI OPNIEUW - "+session.stake+" diamonds","spin");
                }
            } else if(session.type.equals("roulette")) {
                RouletteTable table=rouletteTables.computeIfAbsent(session.block,RouletteTable::new);
                // The numbers are shown in actual clockwise European wheel order.
                int from=page==0?0:18, to=page==0?18:EUROPEAN_WHEEL.length;
                for(int index=from;index<to;index++) {
                    int n=EUROPEAN_WHEEL[index];
                    int slot=index-from==18?34:index-from;
                    button(slot,n==0?Items.EMERALD_BLOCK:RED_NUMBERS.contains(n)?Blocks.WOOL.red().asItem():Blocks.WOOL.black().asItem(),
                        "Wielpositie "+(index+1)+": "+n+" ("+rouletteColor(n)+", "+(n==0?rouletteConfig.zeroPayout:rouletteConfig.numberPayout)+"x)","rn"+n);
                }
                button(18,Blocks.WOOL.red().asItem(),"ROOD - "+rouletteConfig.outsidePayout+"x","rred");button(19,Blocks.WOOL.black().asItem(),"ZWART - "+rouletteConfig.outsidePayout+"x","rblack");
                button(20,Items.IRON_INGOT,"EVEN - "+rouletteConfig.outsidePayout+"x","reven");button(21,Items.GOLD_INGOT,"ONEVEN - "+rouletteConfig.outsidePayout+"x","rodd");
                button(22,Items.SLIME_BALL,"1-18 - "+rouletteConfig.outsidePayout+"x","rlow");button(23,Items.LAPIS_LAZULI,"19-36 - "+rouletteConfig.outsidePayout+"x","rhigh");
                button(24,Items.PAPER,"Dozijn 1 ("+rouletteConfig.groupPayout+"x)","rdozen1");button(25,Items.PAPER,"Dozijn 2 ("+rouletteConfig.groupPayout+"x)","rdozen2");button(26,Items.PAPER,"Dozijn 3 ("+rouletteConfig.groupPayout+"x)","rdozen3");
                button(27,Items.PAPER,"Kolom 1 ("+rouletteConfig.groupPayout+"x)","rcolumn1");button(28,Items.PAPER,"Kolom 2 ("+rouletteConfig.groupPayout+"x)","rcolumn2");button(29,Items.PAPER,"Kolom 3 ("+rouletteConfig.groupPayout+"x)","rcolumn3");
                button(30,Items.ARROW,page==0?"Volgende wielhelft":"Vorige wielhelft","page");
                button(32,Items.CLOCK,table.spinSeconds>0 ? "Wiel draait..." :
                    table.seconds<0 ? "Wacht op eerste inzet" : ("Inzetten sluiten over " + table.seconds + " sec"), "noop");
                int sum=0;for(TableBet b:table.bets)if(b.player.equals(viewer))sum+=b.amount;
                button(33,Items.BOOK,"Jouw geplaatste inzetten: "+sum+" diamonds","noop");
                button(38,Items.GOLD_NUGGET,"Inzet: 1 diamond","bet1");
                button(39,Items.GOLD_INGOT,"Inzet: 5 diamonds","bet5");
                button(40,Items.DIAMOND,"Inzet: 10 diamonds","bet10");
                button(41,Items.DIAMOND_BLOCK,"Inzet: 32 diamonds","bet32");
                button(42,Items.EMERALD_BLOCK,"Inzet: 64 diamonds","bet64");
                button(49,Blocks.CONCRETE.green().asItem(),"Klik inzetvak om "+session.stake+" diamonds te plaatsen","noop");
                button(50,Items.CLOCK,"Volgende ronde automatisch","noop");
                button(35,Items.PAPER,"Nul: "+rouletteConfig.zeroWeight+"/"+(36+rouletteConfig.zeroWeight)+" | Overige nummers elk 1/"+(36+rouletteConfig.zeroWeight),"noop");
            } else if(session.type.equals("horse")) {
                button(4,Items.SADDLE,"PAARDENRACE - Kies een paard","noop");
                for(int i=0;i<5;i++) {
                    ItemStack armor=new ItemStack(Items.LEATHER_HORSE_ARMOR);
                    armor.set(DataComponents.DYED_COLOR,new DyedItemColor(horseColors[i]));
                    buttonStack(19+i*2,armor,(session.selected==i?"> ":"")+NAMES[i]
                        +" | pantser #"+String.format(Locale.ROOT,"%06X",horseColors[i])
                        +" | "+weights[i]+"% | "+multipliers[i]+"x", "horse"+(i+1));
                }
                button(31,Items.NAME_TAG,"Geselecteerd: "+NAMES[session.selected],"noop");
                button(40,Items.GOLD_NUGGET,"Inzet 1 diamond","bet1");
                button(41,Items.GOLD_INGOT,"Inzet 5 diamonds","bet5");
                button(42,Items.DIAMOND,"Inzet 10 diamonds","bet10");
                button(43,Items.DIAMOND_BLOCK,"Inzet 32 diamonds","bet32");
                button(44,Items.EMERALD_BLOCK,"Inzet 64 diamonds","bet64");
                button(49,Blocks.CONCRETE.lime().asItem(),"BEVESTIG "+session.stake+" diamonds op "+NAMES[session.selected],"place");
                button(51,Items.CLOCK,raceTicks>=0?"Race bezig":countdown<0?"Wacht op eerste inzet":"Start over "+countdown+" seconden","noop");
            } else if(session.type.equals("blackjack")) {
                button(4,Items.BOOK,"BLACKJACK - Probeer dichter bij 21 te komen dan de dealer","noop");
                if(session.rounds>0) {
                    button(20,Items.PAPER,"Jouw kaarten: "+session.value,"noop");
                    button(24,Items.PAPER,"Dealer toont: "+session.dealer,"noop");
                    button(39,Blocks.CONCRETE.lime().asItem(),"HIT - nieuwe kaart","hit");
                    button(41,Blocks.CONCRETE.red().asItem(),"STAND - dealer speelt uit","stand");
                } else {
                    button(20,Items.PAPER,"Geen actieve ronde","noop");
                    button(38,Items.GOLD_NUGGET,"Inzet 1 diamond","bet1");
                    button(39,Items.GOLD_INGOT,"Inzet 5 diamonds","bet5");
                    button(40,Items.DIAMOND,"Inzet 10 diamonds","bet10");
                    button(41,Items.DIAMOND_BLOCK,"Inzet 32 diamonds","bet32");
                    button(42,Items.EMERALD_BLOCK,"Inzet 64 diamonds","bet64");
                    button(49,Blocks.CONCRETE.lime().asItem(),"DEEL KAARTEN - "+session.stake+" diamonds","deal");
                }
            } else if(session.type.equals("higherlower")) {
                button(4,Items.BOOK,"HOGER OF LAGER - iedere "+games.higherLowerCorrectPerStep+" juiste gokken +1x","noop");
                if(session.rounds>0) {
                    button(22,Items.PAPER,"Kaart: "+session.value+" | Juist: "+(session.rounds-1)+" | Uitbetaling: "+higherLowerMultiplier(session)+"x","noop");
                    button(38,Blocks.CONCRETE.lime().asItem(),"HOGER","higher");
                    button(40,Blocks.CONCRETE.red().asItem(),"LAGER","lower");
                    long prize=(long)session.stake*higherLowerMultiplier(session);
                    if (session.rounds - 1 >= games.higherLowerMinCorrect) {
                        button(42,Items.DIAMOND,"INNEN - "+prize+" diamonds","cashout");
                    } else {
                        button(42,Items.BARRIER,"INNEN VERGRENDELD - nog "+(games.higherLowerMinCorrect-(session.rounds-1))+" juist nodig","noop");
                    }
                } else {
                    button(22,Items.PAPER,"Begin een nieuw spel","noop");
                    button(38,Items.GOLD_NUGGET,"Inzet 1 diamond","bet1");
                    button(39,Items.GOLD_INGOT,"Inzet 5 diamonds","bet5");
                    button(40,Items.DIAMOND,"Inzet 10 diamonds","bet10");
                    button(41,Items.DIAMOND_BLOCK,"Inzet 32 diamonds","bet32");
                    button(42,Items.EMERALD_BLOCK,"Inzet 64 diamonds","bet64");
                    button(49,Blocks.CONCRETE.lime().asItem(),"START - "+session.stake+" diamonds","deal");
                }
            }
            broadcastChanges();
        }
        @Override public void clicked(int slotIndex,int buttonNum,ContainerInput input,Player player) {
            if(slotIndex>=0&&slotIndex<54) {
                if(player instanceof ServerPlayer sp && sp.getUUID().equals(viewer) && available(sp,session)) {
                    String action=actions.get(slotIndex);
                    if(action!=null)handleGuiClick(sp,this,action);
                }
                broadcastChanges();return;
            }
            // Don't allow moving decorative GUI items or shift-clicking player inventory.
            if(input==ContainerInput.PICKUP && slotIndex>=54)super.clicked(slotIndex,buttonNum,input,player);
        }
        @Override public ItemStack quickMoveStack(Player player,int index){return ItemStack.EMPTY;}
        @Override public void removed(Player p){super.removed(p);openMenus.remove(viewer,this);}
    }
    private void handleGuiClick(ServerPlayer p,CasinoMenu menu,String action) {
        Session s=menu.session;
        if (s.resultExpiresAt >= 0) {
            if (action.equals("closeResult")) {
                s.resultExpiresAt=-1;
                p.closeContainer();
            }
            return;
        }
        if(action.equals("page")){menu.page=1-menu.page;menu.refresh();return;}
        if(!available(p,s))return;
        if(action.startsWith("bet")){
            if(s.rounds>0||slotSpins.containsKey(p.getUUID()))return;
            try{int n=Integer.parseInt(action.substring(3));if(!stakeAllowed(n)){msg(p,"Inzet buiten toegestane limieten.");return;}s.stake=n;}catch(NumberFormatException ignored){return;}
            menu.refresh();return;
        }
        if(s.type.equals("slots")) {
            if(slotSpins.containsKey(p.getUUID())) return;
            if(action.equals("spin"))startSlotSpin(p,s);
        }
        if(s.type.equals("roulette")&&isRouletteChoice(action))placeTableBet(p,s,action);
        if(s.type.equals("horse")) {
            if(action.matches("horse[1-5]"))s.selected=Integer.parseInt(action.substring(5))-1;
            else if(action.equals("place"))placeBet(p,s);
        }
        if(s.type.equals("blackjack")&&Set.of("deal","hit","stand").contains(action))blackjack(p,s,action);
        if(s.type.equals("higherlower")&&Set.of("deal","higher","lower","cashout").contains(action))higherlower(p,s,action);
        menu.refresh();
    }
    private boolean startSlotSpin(ServerPlayer p,Session s) {
        if(!stakeAllowed(s.stake)){msg(p,"Inzet buiten toegestane limieten.");return false;}
        if(slotSpins.containsKey(p.getUUID())){msg(p,"De rollen draaien nog.");return false;}
        if(!debit(p,s.stake))return false;
        int[] symbols={slotSymbol(),slotSymbol(),slotSymbol()};
        slotSpins.put(p.getUUID(),new SlotSpin(p.getUUID(),s.stake,symbols));
        s.lastSlotTitle = null;
        s.lastSlotDetail = null;
        CasinoMenu menu=openMenus.get(p.getUUID());
        if(menu!=null && menu.session==s)menu.refresh();
        dirty=true;save();
        return true;
    }
    private void updateSlotSpins() {
        if(slotSpins.isEmpty())return;
        for(SlotSpin spin:new ArrayList<>(slotSpins.values())) {
            spin.ticks++;
            CasinoMenu menu=openMenus.get(spin.player);
            if(spin.ticks%games.slotAnimationTicks==0&&menu!=null&&menu.session.type.equals("slots")) {
                // Animate visually while retaining the already-chosen server-side outcome.
                for(int i=0;i<3;i++) {
                    int reel=(spin.ticks>=games.slotSpinTicks)?spin.symbols[i]:random.nextInt(6);
                    menu.button(21+i,SLOT_ITEMS[reel],"Rol "+(i+1)+": "+SLOT_LABELS[reel],"noop");
                }
                menu.broadcastChanges();
            }
            if(spin.ticks>=games.slotSpinTicks) {
                int[] a=spin.symbols;
                int multiplier;
                if(a[0]==a[1]&&a[1]==a[2])multiplier=games.slotThreePayout[a[0]];
                else if(a[0]==a[1]||a[0]==a[2]||a[1]==a[2])multiplier=games.slotTwoPayout[a[0]==a[1]||a[0]==a[2]?a[0]:a[1]];
                else multiplier=0;
                long earned=(long)spin.wager*multiplier;
                credit(spin.player,earned);
                ServerPlayer p=server.getPlayerList().getPlayer(spin.player);
                if(p!=null) {
                    String combo=SLOT_LABELS[a[0]]+" / "+SLOT_LABELS[a[1]]+" / "+SLOT_LABELS[a[2]];
                    Session session=sessions.get(spin.player);
                    if(session!=null && session.type.equals("slots")) {
                        session.lastSlotSymbols=a.clone();
                        session.lastSlotTitle=earned>spin.wager?"GEWONNEN!":earned==spin.wager?"INZET TERUG":"HELAAS, GEEN WINST";
                        session.lastSlotDetail=earned>0?
                            "Uitbetaling: "+earned+" diamonds ("+multiplier+"x), inzet "+spin.wager+"." :
                            "Geen winnende combinatie. Verloren: "+spin.wager+" diamonds.";
                        // Slots deliberately do NOT use the timed result screen or close the GUI.
                        session.resultExpiresAt=-1;
                    }
                    msg(p,"Slots: "+combo+". "+(earned>spin.wager?"Gewonnen: "+earned+" diamonds.":earned==spin.wager?"Inzet terug: "+earned+" diamonds.":"Geen winnende combinatie."));
                } else {
                    Session session=sessions.get(spin.player);
                    if(session!=null && session.type.equals("slots")) {
                        session.lastSlotSymbols=a.clone();
                        session.lastSlotTitle=earned>spin.wager?"GEWONNEN!":earned==spin.wager?"INZET TERUG":"HELAAS, GEEN WINST";
                        session.lastSlotDetail=earned>0?"Uitbetaling: "+earned+" diamonds ("+multiplier+"x).":
                            "Geen winnende combinatie. Verloren: "+spin.wager+" diamonds.";
                    }
                }
                slotSpins.remove(spin.player);dirty=true;save();
                if(menu!=null && menu.session.type.equals("slots"))menu.refresh();
            }
        }
    }
    private void placeTableBet(ServerPlayer p,Session s,String choice) {
        if(!stakeAllowed(s.stake)){msg(p,"Inzet buiten toegestane limieten.");return ;}
        RouletteTable table=rouletteTables.computeIfAbsent(s.block,RouletteTable::new);
        if(table.spinSeconds>=0 || table.seconds==0){msg(p,"Het wiel draait al. Wacht tot de volgende ronde.");return;}
        int individual=0, count=0;
        for(TableBet b:table.bets)if(b.player.equals(p.getUUID())){individual+=b.amount;count++;}
        if(individual+s.stake>rouletteConfig.maxTotal||count>=rouletteConfig.maxChips){msg(p,"Maximum "+rouletteConfig.maxTotal+" diamonds en "+rouletteConfig.maxChips+" inzetten per speler per ronde.");return;}
        if(!debit(p,s.stake))return;
        table.bets.add(new TableBet(p.getUUID(),choice,s.stake));
        if(table.seconds<0){table.seconds=rouletteConfig.bettingSeconds;table.phaseDeadline=System.nanoTime()+table.seconds*1_000_000_000L;table.wheelIndex=random.nextInt(EUROPEAN_WHEEL.length);table.displayExpiresAt=-1;}
        updateFloatingLabel(table,rouletteText(tr(rouletteConfig.bettingText),table,table.seconds,-1));
        dirty=true;save();
        msg(p,"Ingezet: "+s.stake+" diamonds op "+rouletteLabel(choice)+". Nog "+table.seconds+" seconden.");
        refreshTableMenus(s.block);
    }
    private void refreshTableMenus(String block) {
        for(CasinoMenu m:new ArrayList<>(openMenus.values()))if(m.session.type.equals("roulette")&&m.session.block.equals(block))m.refresh();
    }
    private void animateRouletteText() {
        long now=System.nanoTime();
        for (RouletteTable table : rouletteTables.values()) if (table.spinSeconds > 0 && now>=table.nextAnimation) {
            table.wheelIndex=(table.wheelIndex+1)%EUROPEAN_WHEEL.length;
            table.nextAnimation=now+(table.spinSeconds<=3?450:table.spinSeconds<=6?250:100)*1_000_000L;
            updateFloatingSpin(table);
        }
    }
    private void updateRouletteTables() {
        long now=System.nanoTime();
        for (RouletteTable table : new ArrayList<>(rouletteTables.values())) {
            if (table.seconds > 0) {
                int remaining=(int)Math.max(0,(table.phaseDeadline-now+999_999_999L)/1_000_000_000L);
                if (remaining==table.seconds) continue;
                table.seconds=remaining;
                if (remaining>0) updateFloatingLabel(table,rouletteText(tr(rouletteConfig.bettingText),table,remaining,-1));
                else {
                    table.seconds=-1;
                    table.spinSeconds=rouletteConfig.minimumSpinSeconds+random.nextInt(rouletteConfig.maximumSpinSeconds-rouletteConfig.minimumSpinSeconds+1);
                    table.phaseDeadline=now+table.spinSeconds*1_000_000_000L;
                    table.nextAnimation=now;
                    table.wheelIndex=random.nextInt(EUROPEAN_WHEEL.length);
                    updateFloatingSpin(table);
                }
                refreshTableMenus(table.block);
            } else if (table.spinSeconds > 0) {
                int remaining=(int)Math.max(0,(table.phaseDeadline-now+999_999_999L)/1_000_000_000L);
                if(remaining==table.spinSeconds) continue;
                table.spinSeconds=remaining;
                if(remaining>0) updateFloatingSpin(table);
                else {table.spinSeconds=-1; settleRoulette(table);}
                refreshTableMenus(table.block);
            }
        }
    }
    private void updateFloatingSpin(RouletteTable table) {
        Display.TextDisplay display=getRouletteDisplay(table);
        if(display==null)return;
        int n=EUROPEAN_WHEEL[table.wheelIndex];
        display.setText(Component.literal(tr(rouletteConfig.title)+"\n").withStyle(ChatFormatting.GOLD,ChatFormatting.BOLD)
            .append(Component.literal(rouletteText(tr(rouletteConfig.spinningText).replace("{seconds}s", "").replace("{seconds}", "").strip(),table,0,n)+"\n").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(tr(n+" "+rouletteColor(n).toUpperCase(Locale.ROOT))+"\n").withColor(rouletteRgb(n)).withStyle(ChatFormatting.BOLD))
            .append(Component.literal(rouletteText(tr(rouletteConfig.betsText),table,0,n)).withStyle(ChatFormatting.GRAY)));
    }
    private void settleRoulette(RouletteTable table) {
        // One randomly sampled number shared by all players at this registered table.
        int winner = rouletteWinner();
        table.lastResult = winner;
        updateFloatingResult(table,winner);
        table.displayExpiresAt = tick + rouletteConfig.resultDisplaySeconds * 20;
        Map<UUID,Long> rewards = new HashMap<>();
        Map<UUID,Integer> stakes = new HashMap<>();
        for (TableBet b : table.bets) {
            rewards.merge(b.player,(long)b.amount*rouletteMultiplier(b.choice,winner),Long::sum);
            stakes.merge(b.player,b.amount,Integer::sum);
        }
        // Persist pending payouts BEFORE delivering them, then clear settled wagers.
        for (var e : rewards.entrySet()) if(e.getValue()>0) unpaid.merge(e.getKey(),e.getValue(),Long::sum);
        table.bets.clear(); dirty=true; save();
        for (UUID id : rewards.keySet()) {
            deliver(id);
            ServerPlayer player=server.getPlayerList().getPlayer(id);
            if(player != null) {
                player.sendSystemMessage(Component.literal("[CasinoCraft] "+tr("Winnende roulette: ")).withStyle(ChatFormatting.WHITE).append(Component.literal(tr(winner+" "+rouletteColor(winner).toUpperCase(Locale.ROOT))).withColor(rouletteRgb(winner)).withStyle(ChatFormatting.BOLD)));
                Session playerSession=sessions.get(id);
                long paid=rewards.get(id);
                String detail="Nummer "+winner+" ("+rouletteColor(winner).toUpperCase(Locale.ROOT)+"). " +
                    (paid==0 ? "Geen van jouw inzetten won." : "Jouw winnende inzetten betaalden "+paid+" diamonds uit.") +
                    " Inzet: "+stakes.getOrDefault(id,0)+" diamonds.";
                if(playerSession!=null && playerSession.type.equals("roulette") && playerSession.block.equals(table.block))
                    showFinalResult(player,playerSession,paid==0?"ROULETTE - VERLOREN":"ROULETTE - UITSLAG",detail);
                else msg(player,"Roulette: "+detail);
            }
        }
        refreshTableMenus(table.block);
    }
    private void expireRouletteDisplays() {
        for (RouletteTable table : rouletteTables.values()) {
            if (table.displayExpiresAt>=0 && tick>=table.displayExpiresAt && table.seconds<0 && table.spinSeconds<0) {
                if (table.display != null && table.display.isAlive()) table.display.discard();
                table.display=null;
                table.displayExpiresAt=-1;
            }
        }
    }
    /** Remove old CasinoCraft roulette displays at this registered table, not arbitrary holograms elsewhere. */
    private void cleanupRouletteDisplays(String block, Display.TextDisplay keep) {
        String[] pieces=block.split("\\|");
        if (pieces.length!=2) return;
        ServerLevel level=null;
        for (ServerLevel l:server.getAllLevels())
            if(l.dimension().identifier().toString().equals(pieces[0])) {level=l;break;}
        if(level==null) return;
        try {
            String[] xyz=pieces[1].split(",");
            BlockPos at=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));
            AABB area=new AABB(at).inflate(2.5);
            for (Display.TextDisplay display : level.getEntitiesOfClass(Display.TextDisplay.class,area,
                e -> e != keep && (e.entityTags().contains(ROULETTE_TAG) ||
                    e.getText().getString().toUpperCase(Locale.ROOT).contains("ROULETTE")))) {
                display.discard();
            }
        } catch (Exception e) {
            System.err.println("[CasinoCraft] Could not cleanup old roulette displays at " + block + ": " + e);
        }
    }
    private Display.TextDisplay getRouletteDisplay(RouletteTable table) {
        if(table.display!=null && table.display.isAlive()) return table.display;
        String[] pieces=table.block.split("\\|");
        if(pieces.length!=2) return null;
        ServerLevel level=null;
        for(ServerLevel l:server.getAllLevels())
            if(l.dimension().identifier().toString().equals(pieces[0])) {level=l;break;}
        if(level==null) return null;
        String[] coords=pieces[1].split(",");
        if(coords.length!=3)return null;
        BlockPos at=new BlockPos(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]),Integer.parseInt(coords[2]));
        // When the old display reference is gone (including after a restart), clean up
        // legacy roulette text displays within the small area of this registered block.
        cleanupRouletteDisplays(table.block,null);
        table.display=EntityTypes.TEXT_DISPLAY.create(level,EntitySpawnReason.COMMAND);
        if(table.display==null)return null;
        table.display.setPos(at.getX()+0.5,at.getY()+1.85,at.getZ()+0.5);
        table.display.setNoGravity(true);
        table.display.addTag(ROULETTE_TAG);
        // Each vanilla client renders the billboard facing its OWN viewpoint.
        table.display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        table.display.setLineWidth(240);
        table.display.setBackgroundColor(0xB0101018);
        table.display.setFlags((byte)1); // text shadow, centered text
        level.addFreshEntity(table.display);
        return table.display;
    }
    private void updateFloatingResult(RouletteTable table,int winner) {
        Display.TextDisplay display=getRouletteDisplay(table);
        if(display==null)return;
        String template=tr(rouletteConfig.resultText);
        var line=Component.literal("");
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\\{number\\}|\\{color\\}").matcher(template);
        int pos=0;
        while(matcher.find()) {
            line.append(Component.literal(rouletteText(template.substring(pos,matcher.start()),table,0,winner)).withStyle(ChatFormatting.WHITE));
            line.append(Component.literal(rouletteText(matcher.group(),table,0,winner)).withColor(rouletteRgb(winner)).withStyle(ChatFormatting.BOLD));
            pos=matcher.end();
        }
        line.append(Component.literal(rouletteText(template.substring(pos),table,0,winner)).withStyle(ChatFormatting.WHITE));
        display.setText(Component.literal(tr(rouletteConfig.title)+"\n").withStyle(ChatFormatting.GOLD,ChatFormatting.BOLD).append(line));
    }
    private void updateFloatingLabel(RouletteTable table,String value) {
        Display.TextDisplay display=getRouletteDisplay(table);
        if(display==null)return;
        display.setText(Component.literal(tr(rouletteConfig.title)+"\n").withStyle(ChatFormatting.GOLD,ChatFormatting.BOLD)
            .append(Component.literal(value+"\n").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(rouletteText(tr(rouletteConfig.betsText),table,0,-1)).withStyle(ChatFormatting.GRAY)));
    }
    private void clearTable(String key) {
        RouletteTable table=rouletteTables.remove(key);
        if(table!=null&&table.display!=null&&table.display.isAlive())table.display.discard();
        cleanupRouletteDisplays(key,null);
    }
    private Path path(){return server.getWorldPath(LevelResource.ROOT).resolve("casinocraft.json");}
    private void load(){
        try {
            if(!Files.exists(path()))return;
            JsonObject root=JsonParser.parseString(Files.readString(path())).getAsJsonObject();
            for(var e:root.getAsJsonObject("blocks").entrySet())blocks.put(e.getKey(),e.getValue().getAsString());
            if(root.has("trackCenter")){JsonObject t=root.getAsJsonObject("trackCenter");trackCenter=new BlockPos(t.get("x").getAsInt(),t.get("y").getAsInt(),t.get("z").getAsInt());trackDimension=t.get("dimension").getAsString();}
            if(root.has("trackDimensions")) {
                JsonObject t=root.getAsJsonObject("trackDimensions");
                double rx=t.get("radiusX").getAsDouble(), rz=t.get("radiusZ").getAsDouble(), w=t.get("laneWidth").getAsDouble();
                if(validTrack(rx,rz,w)){trackRadiusX=rx;trackRadiusZ=rz;laneWidth=w;}
            }
            JsonArray ch=root.getAsJsonArray("chances"),pa=root.getAsJsonArray("payouts");
            for(int i=0;i<5;i++){weights[i]=ch.get(i).getAsInt();multipliers[i]=pa.get(i).getAsInt();}
            if(root.has("horseColors")){
                JsonArray colors=root.getAsJsonArray("horseColors");
                for(int i=0;i<Math.min(5,colors.size());i++)horseColors[i]=colors.get(i).getAsInt() & 0xFFFFFF;
            }
            if(root.has("unpaid"))for(var e:root.getAsJsonObject("unpaid").entrySet())unpaid.put(UUID.fromString(e.getKey()),e.getValue().getAsLong());
            // Crash recovery: pending race bets are refunded; they were debited before racing.
            if(root.has("bets"))for(JsonElement x:root.getAsJsonArray("bets")){JsonObject b=x.getAsJsonObject();unpaid.merge(UUID.fromString(b.get("uuid").getAsString()),b.get("stake").getAsLong(),Long::sum);}
            // Any unsettled roulette bets from a crash are refunded once on recovery.
            if(root.has("slotSpins")) for(JsonElement element:root.getAsJsonArray("slotSpins")) {
                JsonObject spin=element.getAsJsonObject();
                unpaid.merge(UUID.fromString(spin.get("uuid").getAsString()),spin.get("stake").getAsLong(),Long::sum);
            }
            if(root.has("rouletteTables")) for(JsonElement element:root.getAsJsonArray("rouletteTables")) {
                JsonObject t=element.getAsJsonObject();
                for(JsonElement b:t.getAsJsonArray("bets")) {
                    JsonObject bet=b.getAsJsonObject();
                    unpaid.merge(UUID.fromString(bet.get("uuid").getAsString()),bet.get("amount").getAsLong(),Long::sum);
                }
            }
            bets.clear();countdown=-1;raceTicks=-1;dirty=true;
            // Remove stored bets immediately so a repeated crash cannot refund them twice.
            save();
        } catch(Exception e){System.err.println("[CasinoCraft] Failed to load saved data: "+e);}
    }
    private void save(){
        try {
            JsonObject root=new JsonObject(),bs=new JsonObject(),pending=new JsonObject();
            for(var e:blocks.entrySet())bs.addProperty(e.getKey(),e.getValue());root.add("blocks",bs);
            if(trackCenter!=null){JsonObject t=new JsonObject();t.addProperty("x",trackCenter.getX());t.addProperty("y",trackCenter.getY());t.addProperty("z",trackCenter.getZ());t.addProperty("dimension",trackDimension);root.add("trackCenter",t);}
            JsonObject dimensions=new JsonObject();dimensions.addProperty("radiusX",trackRadiusX);dimensions.addProperty("radiusZ",trackRadiusZ);dimensions.addProperty("laneWidth",laneWidth);root.add("trackDimensions",dimensions);
            JsonArray ch=new JsonArray(),pa=new JsonArray(),betArray=new JsonArray();
            for(int n:weights)ch.add(n);for(int n:multipliers)pa.add(n);root.add("chances",ch);root.add("payouts",pa);
            JsonArray colors=new JsonArray();for(int rgb:horseColors)colors.add(rgb);root.add("horseColors",colors);
            for(var e:unpaid.entrySet())pending.addProperty(e.getKey().toString(),e.getValue());root.add("unpaid",pending);
            for(Bet b:bets){JsonObject o=new JsonObject();o.addProperty("uuid",b.player.toString());o.addProperty("horse",b.horse);o.addProperty("stake",b.stake);o.addProperty("multiplier",b.multiplier);betArray.add(o);}root.add("bets",betArray);
            JsonArray rouletteArray=new JsonArray();
            for(RouletteTable t:rouletteTables.values()) if(!t.bets.isEmpty()) {
                JsonObject table=new JsonObject();table.addProperty("block",t.block);JsonArray array=new JsonArray();
                for(TableBet b:t.bets){JsonObject row=new JsonObject();row.addProperty("uuid",b.player.toString());row.addProperty("choice",b.choice);row.addProperty("amount",b.amount);array.add(row);}
                table.add("bets",array);rouletteArray.add(table);
            }
            root.add("rouletteTables",rouletteArray);
            JsonArray spinsArray=new JsonArray();
            for(SlotSpin spin:slotSpins.values()){
                JsonObject row=new JsonObject();row.addProperty("uuid",spin.player.toString());row.addProperty("stake",spin.wager);spinsArray.add(row);
            }
            root.add("slotSpins",spinsArray);
            Path temp=path().resolveSibling("casinocraft.json.tmp");Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(root));
            try{Files.move(temp,path(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temp,path(),StandardCopyOption.REPLACE_EXISTING);}
            dirty=false;
        } catch(Exception e){System.err.println("[CasinoCraft] Failed to save data: "+e);}
    }
}
