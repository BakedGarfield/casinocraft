package be.wevelgem.casinocraft;

import com.google.gson.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.*;
import java.util.*;

/**
 * CasinoCraft alpha. All player actions are gated by a right-click on a registered block.
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
    private static final Set<Integer> RED_NUMBERS = Set.of(
        1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    private final Map<String,String> blocks = new HashMap<>();
    private final Map<UUID,Session> sessions = new HashMap<>();
    private final Map<UUID,Long> unpaid = new HashMap<>();
    private final List<Bet> bets = new ArrayList<>();
    private final Random random = new Random();
    private final int[] weights = DEFAULT_WEIGHTS.clone(), multipliers = DEFAULT_MULTIPLIERS.clone(), horseColors = DEFAULT_HORSE_COLORS.clone();
    private final List<Horse> runners = new ArrayList<>();
    private MinecraftServer server;
    private BlockPos trackCenter;
    private String trackDimension = "minecraft:overworld";
    private int tick, countdown = -1, raceTicks = -1, chosenWinner = -1;
    private final double[] raceProgress = new double[5];
    private boolean dirty;

    private record RouletteChip(String choice, int amount) {}
    private static final int MAX_ROULETTE_TOTAL = 256;
    private static final int MAX_ROULETTE_CHIPS = 64;

    private static final class Session {
        final String type;
        final String block;
        int stake = 1, selected = 0, value, dealer, rounds;
        boolean doubled;
        String roulettePage = "main";
        final List<RouletteChip> rouletteChips = new ArrayList<>();
        Session(String type, String block) { this.type=type; this.block=block; }
    }
    private record Bet(UUID player, int horse, int stake, int multiplier) {}

    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("casinoplay")
                .then(Commands.argument("action", StringArgumentType.word())
                    .executes(ctx -> action(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx,"action")))));
            dispatcher.register(Commands.literal("casino")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("block")
                    .then(Commands.literal("set").then(Commands.argument("game", StringArgumentType.word())
                        .executes(ctx -> registerBlock(ctx.getSource().getPlayerOrException(),StringArgumentType.getString(ctx,"game")))))
                    .then(Commands.literal("remove").executes(ctx -> removeBlock(ctx.getSource().getPlayerOrException()))))
                .then(Commands.literal("horse")
                    .then(Commands.literal("center").executes(ctx -> setCenter(ctx.getSource().getPlayerOrException())))
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
            show(sp,session);
            return InteractionResult.SUCCESS;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (server == null) { server=s; load(); }
            tick++;
            if (tick % 20 == 0) second();
            if (raceTicks >= 0) stepRace();
            if (tick % 200 == 0 && dirty) save();
        });
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
        blocks.put(gameAt(p,pos),game); dirty=true;msg(p,"Geregistreerd: "+game+" op "+pos.toShortString());return 1;
    }
    private int removeBlock(ServerPlayer p) {
        BlockPos pos=targeted(p); if(pos==null)return 0;
        boolean removed=blocks.remove(gameAt(p,pos))!=null;dirty|=removed;msg(p,removed?"Casinoblok verwijderd.":"Geen casinoblok hier.");return removed?1:0;
    }
    private int setCenter(ServerPlayer p) {
        trackCenter=p.blockPosition();trackDimension=p.level().dimension().identifier().toString();dirty=true;
        msg(p,"Midden renbaan ingesteld op "+trackCenter.toShortString()+". Bouw een ovaal met 5 banen rondom dit punt.");return 1;
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
        if(!debit(p,s.stake))return 0;
        int a=random.nextInt(6),b=random.nextInt(6),c=random.nextInt(6);
        int mult=a==b&&b==c?(a==0?12:5):0;
        msg(p,"Slots: "+a+" | "+b+" | "+c+". "+(mult>0?"Gewonnen: "+(s.stake*mult)+" diamonds!":"Geen winst."));
        credit(p.getUUID(),(long)s.stake*mult);return 1;
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
        if(a.equals("deal")&&s.rounds==0){
            if(!debit(p,s.stake))return 0;
            s.value=card()+card();s.dealer=card();s.rounds=1;show(p,s);return 1;
        }
        if(s.rounds==0)return 0;
        if(a.equals("hit")){s.value+=card();if(s.value>21){msg(p,"Bust: "+s.value+". Verloren.");s.rounds=0;}else show(p,s);return 1;}
        if(a.equals("stand")){
            int dealer=s.dealer+card();while(dealer<17)dealer+=card();
            int mult=(dealer>21||s.value>dealer)?2:(s.value==dealer?1:0);
            msg(p,"Jij: "+s.value+" | dealer: "+dealer+". Uitbetaling: "+s.stake*mult+" diamonds.");
            credit(p.getUUID(),(long)s.stake*mult);s.rounds=0;return 1;
        }return 0;
    }
    private int card(){return Math.min(10,random.nextInt(13)+1);}
    private int higherlower(ServerPlayer p,Session s,String a){
        if(a.equals("deal")&&s.rounds==0){if(!debit(p,s.stake))return 0;s.value=random.nextInt(13)+1;s.rounds=1;show(p,s);return 1;}
        if(s.rounds==0)return 0;
        if(a.equals("cashout")){long prize=(long)s.stake*Math.min(32,1L<<Math.min(5,s.rounds-1));credit(p.getUUID(),prize);msg(p,"Geïnd: "+prize+" diamonds.");s.rounds=0;return 1;}
        if(a.equals("higher")||a.equals("lower")){
            int next=random.nextInt(13)+1;
            boolean win=a.equals("higher")?next>s.value:next<s.value;
            msg(p,"Volgende kaart: "+next+(win?". Correct!":". Verloren of gelijkspel."));
            if(win){s.rounds++;s.value=next;show(p,s);}else s.rounds=0;
            return 1;
        }return 0;
    }
    private void second(){
        if(server==null)return;
        for(UUID id:new ArrayList<>(unpaid.keySet()))deliver(id);
        if(countdown>0){countdown--;if(countdown%15==0)broadcast("Paardenrace start over "+countdown+" seconden!");}
        if(countdown==0){countdown=-1;startRace();}
    }
    private void broadcast(String msg){if(server!=null)server.getPlayerList().broadcastSystemMessage(Component.literal("[CasinoCraft] "+msg),false);}
    private void startRace(){
        if(bets.isEmpty()||trackCenter==null||!validChances())return;
        ServerLevel level=server.getAllLevels().iterator().next();
        for(ServerLevel candidate:server.getAllLevels())if(candidate.dimension().identifier().toString().equals(trackDimension)){level=candidate;break;}
        runners.clear();Arrays.fill(raceProgress,0);
        chosenWinner=weightedWinner();raceTicks=0;
        for(int i=0;i<5;i++){
            Horse horse=EntityType.HORSE.create(level,EntitySpawnReason.COMMAND);
            if(horse!=null){horse.setNoAi(true);horse.setInvulnerable(true);
                ItemStack coloredArmor = new ItemStack(Items.LEATHER_HORSE_ARMOR);
                coloredArmor.set(DataComponents.DYED_COLOR, new DyedItemColor(horseColors[i]));
                horse.setItemSlot(EquipmentSlot.BODY, coloredArmor);
                horse.setCustomName(Component.literal(NAMES[i]).withColor(horseColors[i]));horse.setCustomNameVisible(true);
                double[] xy=oval(i,0);horse.setPos(xy[0],trackCenter.getY()+1,xy[1]);level.addFreshEntity(horse);runners.add(horse);}
        }
        broadcast("🐎 Race begonnen! Favoriet: "+NAMES[0]+"; verrassingen mogelijk!");dirty=true;
    }
    private int weightedWinner(){int n=random.nextInt(100);for(int i=0;i<5;i++){n-=weights[i];if(n<0)return i;}return 4;}
    // Five concentric oval lanes. rx/ry are block offsets from the admin-selected center.
    private double[] oval(int lane,double progress){
        double rx=16+lane*3.0,rz=9+lane*3.0;
        double angle=progress*2*Math.PI-Math.PI/2;
        return new double[]{trackCenter.getX()+0.5+rx*Math.cos(angle),trackCenter.getZ()+0.5+rz*Math.sin(angle)};
    }
    private void stepRace(){
        raceTicks++;
        // Predetermined weighted winner but a visible race with position variation.
        // 45 seconds maximum at 20 ticks/s; others finish slightly later.
        for(int i=0;i<5;i++){
            double speed=i==chosenWinner?1.0/720:1.0/(760+i*18);
            raceProgress[i]=Math.min(1,raceProgress[i]+speed);
            if(i<runners.size()){
                Horse h=runners.get(i);if(h.isAlive()){
                    double[] pos=oval(i,raceProgress[i]);
                    h.setPos(pos[0],trackCenter.getY()+1,pos[1]);
                }
            }
        }
        if(raceProgress[chosenWinner]>=1){finishRace();}
    }
    private void finishRace(){
        raceTicks=-1;
        broadcast("🏆 Winnaar: "+NAMES[chosenWinner]+"! Uitbetaling: "+multipliers[chosenWinner]+"x.");
        // Persist pending payouts before attempting delivery. Actual crash-safe ledger requires additional testing.
        for(Bet bet:bets)if(bet.horse==chosenWinner)credit(bet.player,(long)bet.stake*bet.multiplier);
        bets.clear();for(Horse horse:runners)if(horse.isAlive())horse.discard();runners.clear();
        chosenWinner=-1;dirty=true;save();
    }
    private Path path(){return server.getWorldPath(LevelResource.ROOT).resolve("casinocraft.json");}
    private void load(){
        try {
            if(!Files.exists(path()))return;
            JsonObject root=JsonParser.parseString(Files.readString(path())).getAsJsonObject();
            for(var e:root.getAsJsonObject("blocks").entrySet())blocks.put(e.getKey(),e.getValue().getAsString());
            if(root.has("trackCenter")){JsonObject t=root.getAsJsonObject("trackCenter");trackCenter=new BlockPos(t.get("x").getAsInt(),t.get("y").getAsInt(),t.get("z").getAsInt());trackDimension=t.get("dimension").getAsString();}
            JsonArray ch=root.getAsJsonArray("chances"),pa=root.getAsJsonArray("payouts");
            for(int i=0;i<5;i++){weights[i]=ch.get(i).getAsInt();multipliers[i]=pa.get(i).getAsInt();}
            if(root.has("horseColors")){
                JsonArray colors=root.getAsJsonArray("horseColors");
                for(int i=0;i<Math.min(5,colors.size());i++)horseColors[i]=colors.get(i).getAsInt() & 0xFFFFFF;
            }
            if(root.has("unpaid"))for(var e:root.getAsJsonObject("unpaid").entrySet())unpaid.put(UUID.fromString(e.getKey()),e.getValue().getAsLong());
            // Crash recovery: pending race bets are refunded; they were debited before racing.
            if(root.has("bets"))for(JsonElement x:root.getAsJsonArray("bets")){JsonObject b=x.getAsJsonObject();unpaid.merge(UUID.fromString(b.get("uuid").getAsString()),b.get("stake").getAsLong(),Long::sum);}
            bets.clear();countdown=-1;raceTicks=-1;dirty=true;
        } catch(Exception e){System.err.println("[CasinoCraft] Failed to load saved data: "+e);}
    }
    private void save(){
        try {
            JsonObject root=new JsonObject(),bs=new JsonObject(),pending=new JsonObject();
            for(var e:blocks.entrySet())bs.addProperty(e.getKey(),e.getValue());root.add("blocks",bs);
            if(trackCenter!=null){JsonObject t=new JsonObject();t.addProperty("x",trackCenter.getX());t.addProperty("y",trackCenter.getY());t.addProperty("z",trackCenter.getZ());t.addProperty("dimension",trackDimension);root.add("trackCenter",t);}
            JsonArray ch=new JsonArray(),pa=new JsonArray(),betArray=new JsonArray();
            for(int n:weights)ch.add(n);for(int n:multipliers)pa.add(n);root.add("chances",ch);root.add("payouts",pa);
            JsonArray colors=new JsonArray();for(int rgb:horseColors)colors.add(rgb);root.add("horseColors",colors);
            for(var e:unpaid.entrySet())pending.addProperty(e.getKey().toString(),e.getValue());root.add("unpaid",pending);
            for(Bet b:bets){JsonObject o=new JsonObject();o.addProperty("uuid",b.player.toString());o.addProperty("horse",b.horse);o.addProperty("stake",b.stake);o.addProperty("multiplier",b.multiplier);betArray.add(o);}root.add("bets",betArray);
            Path temp=path().resolveSibling("casinocraft.json.tmp");Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(root));
            try{Files.move(temp,path(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temp,path(),StandardCopyOption.REPLACE_EXISTING);}
            dirty=false;
        } catch(Exception e){System.err.println("[CasinoCraft] Failed to save data: "+e);}
    }
}
