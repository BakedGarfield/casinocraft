package be.wevelgem.casinocraft;

import com.google.gson.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
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
    private static final int MAX_ROULETTE_TOTAL = 256;
    private static final int MAX_ROULETTE_CHIPS = 64;

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
        int seconds = -1;
        int lastResult = -1;
        int wheelIndex = 0;
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
    private static final int[] SLOT_THREE_MULT = {12,9,7,5,4,25};
    private static final int[] SLOT_TWO_MULT = {3,2,2,1,1,5};


    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("casinoplay")
                .then(Commands.argument("action", StringArgumentType.word())
                    .executes(ctx -> action(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx,"action")))));
            dispatcher.register(Commands.literal("casino")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
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
            Session session = new Session(type,pos);
            sessions.put(sp.getUUID(),session);
            openCasinoGui(sp,session);
            return InteractionResult.SUCCESS;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (server == null) { server=s; load(); }
            tick++;
            if (tick % 20 == 0) second();
            if (raceTicks >= 0) stepRace();
            updateSlotSpins();
            updateResultScreens();
            if (tick % 4 == 0) animateRouletteText();
            if (tick % 20 == 0) updateRouletteTables();
            if (tick % 200 == 0 && dirty) save();
        });
    }

    /** Keep the result visible for five seconds before closing only that viewer's menu. */
    private void showFinalResult(ServerPlayer p, Session s, String title, String detail) {
        s.resultTitle = title;
        s.resultDetail = detail;
        s.resultExpiresAt = tick + 100;
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
    private void msg(ServerPlayer p,String text){p.sendSystemMessage(Component.literal("[CasinoCraft] "+text));}
    private void link(ServerPlayer p,String title,String action) {
        p.sendSystemMessage(Component.literal("[ "+title+" ]").withStyle(s ->
            s.withColor(0xF5C842).withClickEvent(new ClickEvent.RunCommand("/casinoplay "+action))));
    }
    private void horseLink(ServerPlayer p,int i) {
        String description="Paard "+(i+1)+": "+NAMES[i]+" ["+colorHex(i)+"] | "+weights[i]+"% winstkans | "+multipliers[i]+"x";
        p.sendSystemMessage(Component.literal("[ "+description+" ]").withStyle(style ->
            style.withColor(horseColors[i]).withClickEvent(new ClickEvent.RunCommand("/casinoplay horse"+(i+1)))));
    }
    private boolean available(ServerPlayer p,Session s) {
        if(s==null)return false;
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
        } else if(s.type.equals("higherlower") && s.rounds>0){msg(p,"Huidige kaart: "+s.value+" | reeks: "+s.rounds);link(p,"Hoger","higher");link(p,"Lager","lower");link(p,"Innemen","cashout");return;}
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
        if(a.startsWith("bet") && s.rounds==0) {
            try {int n=Integer.parseInt(a.substring(3)); if(n<1||n>64)return 0;s.stake=n;show(p,s);return 1;} catch(NumberFormatException ignored){return 0;}
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
            if(a.equals("rspin")) return spinRoulette(p,s);
            if(a.equals("rclear")) { s.rouletteChips.clear(); show(p,s); return 1; }
            if(a.equals("rundo")) {
                if(!s.rouletteChips.isEmpty())s.rouletteChips.remove(s.rouletteChips.size()-1);
                show(p,s);return 1;
            }
            if(isRouletteChoice(a)) return addRouletteChip(p,s,a);
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
        if(raceTicks>=0){msg(p,"Race is bezig. Wacht op de volgende.");return 0;}
        if(trackCenter==null){msg(p,"Admin moet eerst /casino horse center instellen.");return 0;}
        if(!validChances()){msg(p,"Admin moet eerst kansen instellen tot 100%.");return 0;}
        if(!debit(p,s.stake))return 0;
        bets.add(new Bet(p.getUUID(),s.selected,s.stake,multipliers[s.selected]));
        if(countdown<0)countdown=60;
        dirty=true;msg(p,"Inzet bevestigd: "+s.stake+" diamonds op "+NAMES[s.selected]+". Start over "+countdown+"s.");return 1;
    }
    private int slots(ServerPlayer p,Session s){
        return startSlotSpin(p,s) ? 1 : 0;
    }
    /** Vanilla clickable chat choices, accessible only after using a registered casino block. */
    private void showRoulette(ServerPlayer p,Session s) {
        switch(s.roulettePage) {
            case "dozens" -> {
                link(p,"1e dozijn: 1-12 (3x)","rdozen1");
                link(p,"2e dozijn: 13-24 (3x)","rdozen2");
                link(p,"3e dozijn: 25-36 (3x)","rdozen3");
            }
            case "columns" -> {
                link(p,"Kolom 1: 1,4,7,...,34 (3x)","rcolumn1");
                link(p,"Kolom 2: 2,5,8,...,35 (3x)","rcolumn2");
                link(p,"Kolom 3: 3,6,9,...,36 (3x)","rcolumn3");
            }
            case "numbers1", "numbers2", "numbers3" -> {
                int page=Integer.parseInt(s.roulettePage.substring(7));
                int from=page==1?0:page==2?13:25;
                int to=page==1?12:page==2?24:36;
                msg(p,"Kies een nummer (0-36). Uitbetaling: 36x inclusief inzet.");
                for(int n=from;n<=to;n++)link(p,"Nummer "+n+" ("+rouletteColor(n)+")","rn"+n);
                link(p,"Nummers 0-12","rnumbers1");
                link(p,"Nummers 13-24","rnumbers2");
                link(p,"Nummers 25-36","rnumbers3");
            }
            default -> {
                link(p,"Rood (2x)","rred");link(p,"Zwart (2x)","rblack");
                link(p,"Even (2x)","reven");link(p,"Oneven (2x)","rodd");
                link(p,"Laag: 1-18 (2x)","rlow");link(p,"Hoog: 19-36 (2x)","rhigh");
                link(p,"Nul / groen (36x)","rn0");
                link(p,"Drie dozijnen (3x)","rdozens");
                link(p,"Drie kolommen (3x)","rcolumns");
                link(p,"Afzonderlijke nummers (36x)","rnumbers1");
            }
        }
        if(!s.roulettePage.equals("main"))link(p,"Terug naar roulette-overzicht","rmain");
        int total=rouletteTotal(s);
        msg(p,"Roulettekaart: "+s.rouletteChips.size()+" inzet(ten), totaal "+total+" diamonds (max "+MAX_ROULETTE_TOTAL+").");
        for(RouletteChip chip:s.rouletteChips)msg(p," • "+rouletteLabel(chip.choice())+": "+chip.amount()+" diamond(s)");
        msg(p,"Kies een inzetvak om "+s.stake+" diamond(s) toe te voegen. Alles draait samen op één nummer.");
        link(p,"DRAAI ROULETTE ("+total+" diamonds)","rspin");
        link(p,"Laatste inzet verwijderen","rundo");
        link(p,"Alle inzetten wissen","rclear");
    }
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
            return n==Integer.parseInt(choice.substring(2))?36:0;
        if(n==0)return 0;
        return switch(choice) {
            case "rred" -> RED_NUMBERS.contains(n)?2:0;
            case "rblack" -> !RED_NUMBERS.contains(n)?2:0;
            case "reven" -> n%2==0?2:0;
            case "rodd" -> n%2!=0?2:0;
            case "rlow" -> n<=18?2:0;
            case "rhigh" -> n>=19?2:0;
            case "rdozen1","rdozen2","rdozen3" -> (n-1)/12==Integer.parseInt(choice.substring(6))-1?3:0;
            case "rcolumn1","rcolumn2","rcolumn3" -> (n-1)%3==Integer.parseInt(choice.substring(7))-1?3:0;
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
        if(s.rouletteChips.size()>=MAX_ROULETTE_CHIPS || rouletteTotal(s)+s.stake>MAX_ROULETTE_TOTAL){
            msg(p,"Maximaal "+MAX_ROULETTE_CHIPS+" inzetten en "+MAX_ROULETTE_TOTAL+" diamonds per draai.");return 0;
        }
        s.rouletteChips.add(new RouletteChip(choice,s.stake));
        msg(p,"Toegevoegd: "+s.stake+" diamonds op "+rouletteLabel(choice)+".");
        show(p,s);return 1;
    }
    private int spinRoulette(ServerPlayer p, Session s) {
        int total=rouletteTotal(s);
        if(total<=0){msg(p,"Kies eerst minstens één inzetvak.");return 0;}
        if(!debit(p,total))return 0;
        int number=random.nextInt(37);
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
            if(!debit(p,s.stake))return 0;
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
            int dealer=s.dealer+card();while(dealer<17)dealer+=card();
            int mult=(dealer>21||s.value>dealer)?2:(s.value==dealer?1:0);
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
    private int higherlower(ServerPlayer p,Session s,String a){
        if (s.resultExpiresAt >= 0) return 0;
        if(a.equals("deal")&&s.rounds==0){if(!debit(p,s.stake))return 0;s.value=random.nextInt(13)+1;s.rounds=1;show(p,s);return 1;}
        if(s.rounds==0)return 0;
        if(a.equals("cashout")){
            long prize=(long)s.stake*Math.min(32,1L<<Math.min(5,s.rounds-1));
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
                horse.setCustomName(Component.literal(NAMES[i]).withColor(horseColors[i]));horse.setCustomNameVisible(true);
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
            int finishTicks = i == chosenWinner ? 720 : 755 + i * 13;
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
        }, Component.literal("CasinoCraft - "+switch(s.type){case "horse" -> "Paardenrace"; case "blackjack" -> "Blackjack"; case "higherlower" -> "Hoger of Lager"; case "roulette" -> "Roulette"; default -> "Slots";})));
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
            buttonStack(slot,new ItemStack(item),title,action);
        }
        void buttonStack(int slot,ItemStack stack,String title,String action){
            actions.put(slot,action);
            stack.set(DataComponents.CUSTOM_NAME,Component.literal(title));
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
                button(10,Items.PAPER,"3 gelijk: Diamond 12x | Emerald 9x | Gold 7x","noop");
                button(11,Items.PAPER,"Redstone 5x | Amethyst 4x | Nether Star 25x","noop");
                button(12,Items.PAPER,"2 gelijk: symbolen betalen 1-5x","noop");
                // Keep the last THREE actual outcomes visible after a spin until the next spin starts.
                int[] reel = activeSpin != null ? activeSpin.symbols :
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
                int from=page==0?0:19, to=page==0?19:EUROPEAN_WHEEL.length;
                for(int index=from;index<to;index++) {
                    int n=EUROPEAN_WHEEL[index];
                    int slot=index-from;
                    button(slot,n==0?Items.EMERALD_BLOCK:RED_NUMBERS.contains(n)?Blocks.WOOL.red().asItem():Blocks.WOOL.black().asItem(),
                        "Wielpositie "+(index+1)+": "+n+" ("+rouletteColor(n)+", 36x)","rn"+n);
                }
                button(18,Blocks.WOOL.red().asItem(),"ROOD - 2x","rred");button(19,Blocks.WOOL.black().asItem(),"ZWART - 2x","rblack");
                button(20,Items.IRON_INGOT,"EVEN - 2x","reven");button(21,Items.GOLD_INGOT,"ONEVEN - 2x","rodd");
                button(22,Items.SLIME_BALL,"1-18 - 2x","rlow");button(23,Items.LAPIS_LAZULI,"19-36 - 2x","rhigh");
                button(24,Items.PAPER,"Dozijn 1 (3x)","rdozen1");button(25,Items.PAPER,"Dozijn 2 (3x)","rdozen2");button(26,Items.PAPER,"Dozijn 3 (3x)","rdozen3");
                button(27,Items.PAPER,"Kolom 1 (3x)","rcolumn1");button(28,Items.PAPER,"Kolom 2 (3x)","rcolumn2");button(29,Items.PAPER,"Kolom 3 (3x)","rcolumn3");
                button(30,Items.ARROW,page==0?"Volgende wielhelft":"Vorige wielhelft","page");
                button(32,Items.CLOCK,table.seconds<0?"Wacht op eerste inzet":("Sluit over "+table.seconds+" sec"),"noop");
                int sum=0;for(TableBet b:table.bets)if(b.player.equals(viewer))sum+=b.amount;
                button(33,Items.BOOK,"Jouw geplaatste inzetten: "+sum+" diamonds","noop");
                button(38,Items.GOLD_NUGGET,"Inzet: 1 diamond","bet1");
                button(39,Items.GOLD_INGOT,"Inzet: 5 diamonds","bet5");
                button(40,Items.DIAMOND,"Inzet: 10 diamonds","bet10");
                button(41,Items.DIAMOND_BLOCK,"Inzet: 32 diamonds","bet32");
                button(42,Items.EMERALD_BLOCK,"Inzet: 64 diamonds","bet64");
                button(49,Blocks.CONCRETE.green().asItem(),"Klik inzetvak om "+session.stake+" diamonds te plaatsen","noop");
                button(50,Items.CLOCK,"Volgende ronde automatisch","noop");
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
                button(4,Items.BOOK,"HOGER OF LAGER - Raad de volgende kaart","noop");
                if(session.rounds>0) {
                    button(22,Items.PAPER,"Huidige kaart: "+session.value+" | reeks: "+session.rounds,"noop");
                    button(38,Blocks.CONCRETE.lime().asItem(),"HOGER","higher");
                    button(40,Blocks.CONCRETE.red().asItem(),"LAGER","lower");
                    long prize=(long)session.stake*Math.min(32,1L<<Math.min(5,session.rounds-1));
                    button(42,Items.DIAMOND,"INNEN - "+prize+" diamonds","cashout");
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
        if(action.startsWith("bet")){
            try{s.stake=Integer.parseInt(action.substring(3));}catch(NumberFormatException ignored){return;}
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
        if(slotSpins.containsKey(p.getUUID())){msg(p,"De rollen draaien nog.");return false;}
        if(!debit(p,s.stake))return false;
        int[] symbols={random.nextInt(6),random.nextInt(6),random.nextInt(6)};
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
            if(spin.ticks%3==0&&menu!=null&&menu.session.type.equals("slots")) {
                // Animate visually while retaining the already-chosen server-side outcome.
                for(int i=0;i<3;i++) {
                    int reel=(spin.ticks>=30)?spin.symbols[i]:random.nextInt(6);
                    menu.button(21+i,SLOT_ITEMS[reel],"Rol "+(i+1)+": "+SLOT_LABELS[reel],"noop");
                }
                menu.broadcastChanges();
            }
            if(spin.ticks>=30) {
                int[] a=spin.symbols;
                int multiplier;
                if(a[0]==a[1]&&a[1]==a[2])multiplier=SLOT_THREE_MULT[a[0]];
                else if(a[0]==a[1]||a[0]==a[2]||a[1]==a[2])multiplier=SLOT_TWO_MULT[a[0]==a[1]||a[0]==a[2]?a[0]:a[1]];
                else multiplier=0;
                long earned=(long)spin.wager*multiplier;
                credit(spin.player,earned);
                ServerPlayer p=server.getPlayerList().getPlayer(spin.player);
                if(p!=null) {
                    String combo=SLOT_LABELS[a[0]]+" / "+SLOT_LABELS[a[1]]+" / "+SLOT_LABELS[a[2]];
                    Session session=sessions.get(spin.player);
                    if(session!=null && session.type.equals("slots")) {
                        session.lastSlotSymbols=a.clone();
                        session.lastSlotTitle=earned>0?"GEWONNEN!":"HELAAS, GEEN WINST";
                        session.lastSlotDetail=earned>0?
                            "Uitbetaling: "+earned+" diamonds ("+multiplier+"x), inzet "+spin.wager+"." :
                            "Geen winnende combinatie. Verloren: "+spin.wager+" diamonds.";
                        // Slots deliberately do NOT use the timed result screen or close the GUI.
                        session.resultExpiresAt=-1;
                    }
                    msg(p,"Slots: "+combo+". "+(earned>0?"Gewonnen: "+earned+" diamonds.":"Geen winnende combinatie."));
                } else {
                    Session session=sessions.get(spin.player);
                    if(session!=null && session.type.equals("slots")) {
                        session.lastSlotSymbols=a.clone();
                        session.lastSlotTitle=earned>0?"GEWONNEN!":"HELAAS, GEEN WINST";
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
        RouletteTable table=rouletteTables.computeIfAbsent(s.block,RouletteTable::new);
        if(table.seconds==0){msg(p,"Wacht tot de volgende ronde.");return;}
        int individual=0, count=0;
        for(TableBet b:table.bets)if(b.player.equals(p.getUUID())){individual+=b.amount;count++;}
        if(individual+s.stake>MAX_ROULETTE_TOTAL||count>=MAX_ROULETTE_CHIPS){msg(p,"Maximum 256 diamonds en 64 inzetten per speler per ronde.");return;}
        if(!debit(p,s.stake))return;
        table.bets.add(new TableBet(p.getUUID(),choice,s.stake));
        if(table.seconds<0){table.seconds=10;table.wheelIndex=random.nextInt(EUROPEAN_WHEEL.length);}
        updateFloatingLabel(table,"DRAAIT OVER "+table.seconds+"s");
        dirty=true;save();
        msg(p,"Ingezet: "+s.stake+" diamonds op "+rouletteLabel(choice)+". Nog "+table.seconds+" seconden.");
        refreshTableMenus(s.block);
    }
    private void refreshTableMenus(String block) {
        for(CasinoMenu m:new ArrayList<>(openMenus.values()))if(m.session.type.equals("roulette")&&m.session.block.equals(block))m.refresh();
    }
    private void animateRouletteText() {
        for(RouletteTable table:rouletteTables.values())if(table.seconds>0&&table.seconds<=5) {
            table.wheelIndex=(table.wheelIndex+1)%EUROPEAN_WHEEL.length;
            updateFloatingResult(table,EUROPEAN_WHEEL[table.wheelIndex]);
        }
    }
    private void updateRouletteTables() {
        for(RouletteTable table:new ArrayList<>(rouletteTables.values())) {
            if(table.seconds>0) {table.seconds--;
                if(table.seconds>5)updateFloatingLabel(table,"DRAAIT OVER "+table.seconds+"s");
                refreshTableMenus(table.block);
            }
            if(table.seconds==0) {
                table.seconds=-1;
                int winner=random.nextInt(37);
                table.lastResult=winner;
                updateFloatingResult(table,winner);
                Map<UUID,Long> rewards=new HashMap<>();
                Map<UUID,Integer> stakes=new HashMap<>();
                for(TableBet b:table.bets) {
                    rewards.merge(b.player,(long)b.amount*rouletteMultiplier(b.choice,winner),Long::sum);
                    stakes.merge(b.player,b.amount,Integer::sum);
                }
                // Record unpaid credits before leaving the round.
                for(var e:rewards.entrySet())if(e.getValue()>0)unpaid.merge(e.getKey(),e.getValue(),Long::sum);
                table.bets.clear();dirty=true;save();
                for(UUID id:rewards.keySet()){
                    deliver(id);ServerPlayer p=server.getPlayerList().getPlayer(id);
                    if(p!=null) {
                        Session playerSession=sessions.get(id);
                        long paid=rewards.get(id);
                        String detail="Nummer "+winner+" ("+rouletteColor(winner).toUpperCase(Locale.ROOT)+"). " +
                            (paid==0 ? "Geen van jouw inzetten won." : "Jouw winnende inzetten betaalden "+paid+" diamonds uit.") +
                            " Inzet: "+stakes.getOrDefault(id,0)+" diamonds.";
                        if(playerSession!=null && playerSession.type.equals("roulette") && playerSession.block.equals(table.block))
                            showFinalResult(p,playerSession,paid==0?"ROULETTE - VERLOREN":"ROULETTE - UITSLAG",detail);
                        else msg(p,"Roulette: "+detail);
                    }
                }
                refreshTableMenus(table.block);
            }
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
        table.display=EntityTypes.TEXT_DISPLAY.create(level,EntitySpawnReason.COMMAND);
        if(table.display==null)return null;
        table.display.setPos(at.getX()+0.5,at.getY()+1.85,at.getZ()+0.5);
        table.display.setNoGravity(true);
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
        ChatFormatting color = winner==0 ? ChatFormatting.GREEN :
            RED_NUMBERS.contains(winner) ? ChatFormatting.RED : ChatFormatting.GRAY;
        display.setText(Component.literal("✦ ROULETTE ✦\n").withStyle(ChatFormatting.GOLD,ChatFormatting.BOLD)
            .append(Component.literal("WINNAAR: ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(winner+"  "+rouletteColor(winner).toUpperCase(Locale.ROOT)).withStyle(color,ChatFormatting.BOLD)));
    }
    private void updateFloatingLabel(RouletteTable table,String value) {
        Display.TextDisplay display=getRouletteDisplay(table);
        if(display==null)return;
        display.setText(Component.literal("✦ ROULETTE ✦\n").withStyle(ChatFormatting.GOLD,ChatFormatting.BOLD)
            .append(Component.literal(value+"\n").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(table.bets.size()+" inzet(ten) geplaatst").withStyle(ChatFormatting.GRAY)));
    }
    private void clearTable(String key) {
        RouletteTable table=rouletteTables.remove(key);
        if(table!=null&&table.display!=null&&table.display.isAlive())table.display.discard();
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
