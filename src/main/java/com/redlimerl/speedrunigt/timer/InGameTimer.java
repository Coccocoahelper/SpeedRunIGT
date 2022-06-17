package com.redlimerl.speedrunigt.timer;

import com.google.gson.Gson;
import com.redlimerl.speedrunigt.SpeedRunIGT;
import com.redlimerl.speedrunigt.crypt.Crypto;
import com.redlimerl.speedrunigt.option.SpeedRunOption;
import com.redlimerl.speedrunigt.option.SpeedRunOptions;
import com.redlimerl.speedrunigt.timer.category.InvalidCategoryException;
import com.redlimerl.speedrunigt.timer.category.RunCategories;
import com.redlimerl.speedrunigt.timer.category.RunCategory;
import com.redlimerl.speedrunigt.timer.category.condition.CategoryCondition;
import com.redlimerl.speedrunigt.timer.logs.TimerPauseLog;
import com.redlimerl.speedrunigt.timer.logs.TimerTickLog;
import com.redlimerl.speedrunigt.timer.logs.TimerTimeline;
import com.redlimerl.speedrunigt.timer.packet.TimerPacketUtils;
import com.redlimerl.speedrunigt.timer.packet.packets.*;
import com.redlimerl.speedrunigt.timer.running.RunPortalPos;
import com.redlimerl.speedrunigt.timer.running.RunType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static com.redlimerl.speedrunigt.timer.InGameTimerUtils.millisecondToStringFormat;
import static com.redlimerl.speedrunigt.timer.InGameTimerUtils.timeToStringFormat;

/**
 * In-game Timer class.
 * {@link TimerStatus}
 */
@SuppressWarnings("unused")
public class InGameTimer implements Serializable {

    @NotNull
    static InGameTimer INSTANCE = new InGameTimer("");
    @NotNull
    static InGameTimer COMPLETED_INSTANCE = new InGameTimer("");

    private static final String cryptKey = "faRQOs2GK5j863eP";
    private static final int DATA_VERSION = 4;

    @NotNull
    public static InGameTimer getInstance() { return INSTANCE; }

    private static final ArrayList<Consumer<InGameTimer>> onCompleteConsumers = new ArrayList<>();

    @SuppressWarnings("unused")
    public static void onComplete(Consumer<InGameTimer> supplier) {
        onCompleteConsumers.add(supplier);
    }

    public InGameTimer(String worldName) {
        this(worldName, true);
    }
    public InGameTimer(String worldName, boolean isResettable) {
        this.worldName = worldName;
        this.isResettable = isResettable;
    }

    String worldName;
    final UUID uuid = UUID.randomUUID();
    private String category = RunCategories.ANY.getID();
    private final boolean isResettable;
    private boolean isCompleted = false;
    boolean isServerIntegrated = true;
    boolean isCoop = false;
    RunType runType = RunType.RANDOM_SEED;
    private boolean isGlitched = false;
    private int completeCount = 0;

    //Timer time
    long startTime = 0;
    long endTime = 0;
    private long endIGTTime = 0;
    private long retimedIGTTime = 0;
    private long rebaseIGTime = 0;
    private long excludedTime = 0; //for AA
    private long leastTickTime = 0;
    private long leastStartTime = 0;
    private int activateTicks = 0;
    Long lanOpenedTime = null;

    private long leaveTime = 0;
    private int pauseCount = 0;

    //Logs
    private String firstInput = "";
    private final CopyOnWriteArrayList<TimerPauseLog> pauseLogList = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<TimerTickLog> freezeLogList = new CopyOnWriteArrayList<>();

    //For logging var
    private int loggerTicks = 0;
    private long loggerPausedTime = 0;
    private int pauseTriggerTick = 0;
    private String prevPauseReason = "";

    //For checking blind
    CopyOnWriteArrayList<RunPortalPos> lastOverWorldPortalPos = new CopyOnWriteArrayList<>();
    CopyOnWriteArrayList<RunPortalPos> lastNetherPortalPos = new CopyOnWriteArrayList<>();
    CopyOnWriteArrayList<RunPortalPos> endPortalPosList = new CopyOnWriteArrayList<>();

    //For record
    private final CopyOnWriteArrayList<TimerTimeline> timelines = new CopyOnWriteArrayList<>();
    private final TimerAdvancementTracker advancementsTracker = new TimerAdvancementTracker();
    private boolean isHardcore = false;

    private final Integer dataVersion = DATA_VERSION;

    @NotNull
    private TimerStatus status = TimerStatus.NONE;

    private final ConcurrentHashMap<Integer, Integer> moreData = new ConcurrentHashMap<>();
    private CategoryCondition customCondition = null;

    /**
     * Start the Timer, Trigger when player to join(created) the world
     */
    public static void start(String worldName, RunType runType) {
        if (!INSTANCE.worldName.isEmpty()) {
            INSTANCE.writeRecordFile(false);
        }
        INSTANCE = new InGameTimer(worldName);
        INSTANCE.setCategory(SpeedRunOption.getOption(SpeedRunOptions.TIMER_CATEGORY), false);
        INSTANCE.setPause(true, TimerStatus.IDLE, "startup");
        INSTANCE.isGlitched = SpeedRunOption.getOption(SpeedRunOptions.TIMER_LEGACY_IGT_MODE);
        INSTANCE.runType = runType;
    }

    /**
     * Start the Timer, Trigger when player to join(created) the world
     */
    public static void reset() {
        if (INSTANCE.isCompleted || INSTANCE.getStatus() == TimerStatus.COMPLETED_LEGACY) return;
        RunType runType = INSTANCE.getRunType();
        boolean isGlitched = INSTANCE.isGlitched;
        boolean isCoop = INSTANCE.isCoop;

        INSTANCE = new InGameTimer(INSTANCE.worldName, false);
        INSTANCE.setCategory(RunCategories.CUSTOM, false);
        INSTANCE.runType = runType;
        INSTANCE.isGlitched = isGlitched;
        INSTANCE.isCoop = isCoop;
        INSTANCE.setPause(true, TimerStatus.IDLE, "reset");
        INSTANCE.setPause(false, "reset");
        if (isCoop && SpeedRunIGT.IS_CLIENT_SIDE) TimerPacketUtils.sendClient2ServerPacket(MinecraftClient.getInstance(), new TimerInitPacket(INSTANCE, INSTANCE.getRealTimeAttack()));
    }

    /**
     * End the Timer, Trigger when player leave
     */
    public static void end() {
        INSTANCE.setStatus(TimerStatus.NONE);
    }

    /**
     * End the Timer, Trigger when Complete Ender Dragon
     */
    public static void complete() {
        complete(System.currentTimeMillis(), true);
    }

    /**
     * End the Timer, Trigger when Complete Ender Dragon
     */
    public synchronized static void complete(long endTime, boolean canSendPacket) {
        if (INSTANCE.isCompleted || !INSTANCE.isStarted()) return;

        // Init additional data
        INSTANCE.isHardcore = InGameTimerUtils.isHardcoreWorld();

        COMPLETED_INSTANCE = new Gson().fromJson(new Gson().toJson(INSTANCE) + "", InGameTimer.class);
        INSTANCE.isCompleted = true;
        InGameTimer timer = COMPLETED_INSTANCE;

        timer.endTime = endTime;
        timer.endIGTTime = timer.endTime - timer.leastTickTime;
        timer.setStatus(TimerStatus.COMPLETED_LEGACY);

        if (timer.isCoop() && canSendPacket && SpeedRunIGT.IS_CLIENT_SIDE) TimerPacketUtils.sendClient2ServerPacket(MinecraftClient.getInstance(), new TimerCompletePacket(timer.getRealTimeAttack()));

        if (INSTANCE.isServerIntegrated) {
            StringBuilder resultLog = new StringBuilder();
            boolean isRetimed = timer.getRetimedInGameTime() != timer.getInGameTime(false);
            resultLog.append("Result > IGT: ").append(timeToStringFormat(timer.getInGameTime(false)));
            if (isRetimed) resultLog.append(", Auto-Retimed IGT: ").append(timeToStringFormat(timer.getRetimedInGameTime()));
            resultLog.append(", RTA: ").append(timeToStringFormat(timer.getRealTimeAttack()))
                    .append(", Counted Ticks: ").append(timer.activateTicks)
                    .append(", Total Ticks: ").append(timer.loggerTicks);
            if (isRetimed) resultLog.append(", Auto-Retimed IGT Length: ").append(timeToStringFormat(timer.retimedIGTTime));
            if (timer.getCategory() == RunCategories.ALL_ADVANCEMENTS)
                resultLog.append(", Excluded RTA Time: ").append(timeToStringFormat(timer.excludedTime));

            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yy.MM.dd HH:mm:ss");
            String logInfo = "====================\r\n"
                    + resultLog + "\r\n"
                    + timer.firstInput + "\r\n"
                    + "MC Version : " + InGameTimerUtils.getMinecraftVersion() + "\r\n"
                    + "Timer Version : " + SpeedRunIGT.MOD_VERSION + "\r\n"
                    + "Run Date : " + simpleDateFormat.format(new Date());

            String worldName = INSTANCE.worldName;
            File worldDir = InGameTimerUtils.getTimerLogDir(worldName, "logs");
            if (worldDir == null) return;

            File advancementFile = new File(worldDir, "igt_advancement" + timer.getLogSuffix()),
                    pauseFile = new File(worldDir, "igt_timer" + timer.getLogSuffix()),
                    tickFile = new File(worldDir, "igt_freeze" + timer.getLogSuffix()),
                    categoryFile = new File(worldDir, "igt_category" + timer.getLogSuffix());
            String advancementLog = SpeedRunIGT.GSON.toJson(timer.getAdvancementsTracker().getAdvancements()),
                    pauseLog = InGameTimerUtils.pauseLogListToString(timer.pauseLogList, !pauseFile.exists(), pauseFile.exists() ? 0 : timer.completeCount) + logInfo,
                    freezeLog = InGameTimerUtils.logListToString(timer.freezeLogList, tickFile.exists() ? 0 : timer.completeCount),
                    categoryRaw = timer.getCategory().getConditionJson() != null ? SpeedRunIGT.PRETTY_GSON.toJson(timer.getCategory().getConditionJson()) : "";
            timer.freezeLogList.clear();
            timer.pauseLogList.clear();
            saveManagerThread.submit(() -> {
                try {
                    FileUtils.writeStringToFile(advancementFile, advancementLog, StandardCharsets.UTF_8);
                    FileUtils.writeStringToFile(pauseFile, pauseLog, StandardCharsets.UTF_8, true);
                    FileUtils.writeStringToFile(tickFile, freezeLog, StandardCharsets.UTF_8, true);
                    if (!categoryRaw.isEmpty()) FileUtils.writeStringToFile(categoryFile, categoryRaw, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    e.printStackTrace();
                    SpeedRunIGT.error("Failed to save timer logs :( RTA : " + timeToStringFormat(timer.getRealTimeAttack()) + " / IGT : " + timeToStringFormat(timer.getInGameTime(false)));
                }
            });
        }
        INSTANCE.completeCount++;

        INSTANCE.updateRecordString();
        INSTANCE.writeRecordFile(false);

        for (Consumer<InGameTimer> onCompleteConsumer : onCompleteConsumers) {
            try {
                onCompleteConsumer.accept(timer);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void leave() {
        if (!INSTANCE.isServerIntegrated) return;

        INSTANCE.leaveTime = System.currentTimeMillis();
        INSTANCE.pauseCount = 0;
        INSTANCE.setPause(true, TimerStatus.LEAVE, "leave the world");

        save(true);

        INSTANCE.setStatus(TimerStatus.NONE);
    }

    private static boolean waitingSaveTask = false;
    private static final ExecutorService saveManagerThread = Executors.newFixedThreadPool(2);
    private static void save() { save(false); }
    private static synchronized void save(boolean withLeave) {
        if (waitingSaveTask || saveManagerThread.isShutdown() || saveManagerThread.isTerminated() || !INSTANCE.isServerIntegrated || INSTANCE.worldName.isEmpty()) return;

        String worldName = INSTANCE.worldName, timerData = SpeedRunIGT.GSON.toJson(INSTANCE) + "", completeData = SpeedRunIGT.GSON.toJson(COMPLETED_INSTANCE) + "";
        File worldDir = InGameTimerUtils.getTimerLogDir(worldName, "data");
        if (worldDir == null) return;

        if (withLeave) end();

        waitingSaveTask = true;
        saveManagerThread.submit(() -> {
            try {
                if (worldDir.exists()) {
                    File timerFile = new File(worldDir, "timer.igt");
                    File timerCompleteFile = new File(worldDir, "timer.c.igt");
                    File oldTimerFile = new File(worldDir, "timer.igt.old");
                    File oldTimerCompleteFile = new File(worldDir, "timer.c.igt.old");

                    // Old data backup
                    if (timerFile.exists()) {
                        if (oldTimerFile.exists()) FileUtils.forceDelete(oldTimerFile);
                        FileUtils.moveFile(timerFile, oldTimerFile);
                        if (timerCompleteFile.exists()) {
                            if (oldTimerCompleteFile.exists()) FileUtils.forceDelete(oldTimerCompleteFile);
                            FileUtils.moveFile(timerCompleteFile, oldTimerCompleteFile);
                        }
                        else if (oldTimerCompleteFile.exists()) FileUtils.deleteQuietly(oldTimerCompleteFile);
                    }

                    // Save data
                    FileUtils.writeStringToFile(timerFile, Crypto.encrypt(timerData, cryptKey), StandardCharsets.UTF_8);
                    if (INSTANCE.isCompleted) FileUtils.writeStringToFile(timerCompleteFile, Crypto.encrypt(completeData, cryptKey), StandardCharsets.UTF_8);

                    waitingSaveTask = false;
                }
            } catch (Throwable e) {
                e.printStackTrace();
                SpeedRunIGT.error("Failed to save timer data's :(");
            }
        });
    }

    public static boolean load(String name) {
        File worldDir = InGameTimerUtils.getTimerLogDir(name, "data");
        if (worldDir == null) return false;

        String isOld = "";
        while (true) {
            File file = new File(worldDir, "timer.igt"+isOld);
            File completeFile = new File(worldDir, "timer.c.igt"+isOld);
            if (file.exists()) {
                try {
                    INSTANCE = SpeedRunIGT.GSON.fromJson(Crypto.decrypt(FileUtils.readFileToString(file, StandardCharsets.UTF_8), cryptKey), InGameTimer.class);
                    if (completeFile.exists()) COMPLETED_INSTANCE = SpeedRunIGT.GSON.fromJson(Crypto.decrypt(FileUtils.readFileToString(completeFile, StandardCharsets.UTF_8), cryptKey), InGameTimer.class);
                    SpeedRunIGT.debug("Loaded Timer Saved Data! " + isOld);
                    INSTANCE.setStatus(TimerStatus.LEAVE);

                    //noinspection ConstantConditions
                    if (INSTANCE.dataVersion == null || INSTANCE.dataVersion == 0 || INSTANCE.dataVersion != DATA_VERSION) {
                        FileUtils.moveFile(file, new File(worldDir, "timer.igt.backup"));
                        SpeedRunIGT.error("The timer data has found, but it is an old version. timer file is renamed to \"*.igt.backup\"");
                        InGameTimer.start(name, RunType.OLD_WORLD);
                        return true;
                    }

                    INSTANCE.worldName = name;
                    COMPLETED_INSTANCE.worldName = name;

                    return true;
                } catch (Throwable e) {
                    if (!isOld.isEmpty()) return false;
                    isOld = ".old";
                }
            } else if (SpeedRunOption.getOption(SpeedRunOptions.TIMER_START_GENERATED_WORLD) && isOld.isEmpty()) {
                InGameTimer.start(name, RunType.OLD_WORLD);
                return true;
            } else return false;
        }
    }

    private String resultRecord = "";
    private void updateRecordString() {
        resultRecord = SpeedRunIGT.PRETTY_GSON.toJson(InGameTimerUtils.convertTimelineJson(this));
    }
    public void writeRecordFile(boolean worldOnly) {
        File recordFile = new File(SpeedRunIGT.getRecordsPath().toFile(), uuid + ".json");
        File worldFile = InGameTimerUtils.getTimerLogDir(this.worldName, "");
        if (worldFile == null && isServerIntegrated) return;
        File worldRecordFile = !isServerIntegrated ? null : new File(worldFile, "record.json");
        if (resultRecord.isEmpty()) return;

        SpeedRunOptions.RecordGenerateType optionType = SpeedRunOption.getOption(SpeedRunOptions.GENERATE_RECORD_FILE);
        if (optionType == SpeedRunOptions.RecordGenerateType.NONE
                || (optionType == SpeedRunOptions.RecordGenerateType.COMPLETE_ONLY && !this.isCompleted())) return;

        saveManagerThread.submit(() -> {
            try {
                if (!worldOnly) FileUtils.writeStringToFile(recordFile, resultRecord, StandardCharsets.UTF_8);
                if (worldRecordFile != null) FileUtils.writeStringToFile(worldRecordFile, resultRecord, StandardCharsets.UTF_8);
                System.setProperty("speedrunigt.record", recordFile.getName());
            } catch (IOException e) {
                e.printStackTrace();
                SpeedRunIGT.error("Failed to write timer record :(");
            }
        });
    }

    public @NotNull RunCategory getCategory() {
        return RunCategory.getCategory(category);
    }

    public void setCategory(RunCategory category, boolean canSendPacket) {
        this.category = category.getID();
        if (category.getConditionJson() != null) {
            try {
                this.customCondition = new CategoryCondition(category.getConditionJson());
            } catch (InvalidCategoryException exception) {
                InGameTimerUtils.setCategoryWarningScreen(category.getConditionFileName(), exception);
            }
        }
        if (isCoop() && canSendPacket && SpeedRunIGT.IS_CLIENT_SIDE) TimerPacketUtils.sendClient2ServerPacket(MinecraftClient.getInstance(), new TimerChangeCategoryPacket(this.category));
    }

    public boolean isCoop() {
        return isCoop;
    }

    public long getTicks() {
        return this.activateTicks;
    }

    public int getMoreData(int key) {
        return moreData.getOrDefault(key, 0);
    }

    public Enumeration<Integer> getMoreDataKeys() {
        return moreData.keys();
    }

    public void updateMoreData(int key, int value) {
        this.updateMoreData(key, value, true);
    }

    public void updateMoreData(int key, int value, boolean canSendPacket) {
        moreData.put(key, value);
        if (this.isCoop() && canSendPacket && SpeedRunIGT.IS_CLIENT_SIDE) TimerPacketUtils.sendClient2ServerPacket(MinecraftClient.getInstance(), new TimerDataConditionPacket(key, value));
    }

    public @NotNull TimerStatus getStatus() {
        return status;
    }

    public void setStatus(@NotNull TimerStatus status) {
        if (this.getStatus() == TimerStatus.COMPLETED_LEGACY && status != TimerStatus.NONE) return;
        this.status = status;
    }

    public void setUncompleted(boolean canSendPacket) {
        if (!this.isCompleted) return;
        this.isCompleted = false;
        if (canSendPacket && this.isCoop() && SpeedRunIGT.IS_CLIENT_SIDE) TimerPacketUtils.sendClient2ServerPacket(MinecraftClient.getInstance(), new TimerUncompletedPacket());
    }

    public boolean isCompleted() {
        return this.isCompleted || this.getStatus() == TimerStatus.COMPLETED_LEGACY;
    }

    public int getPauseCount() {
        return pauseCount;
    }

    public boolean isStarted() {
        return this.startTime != 0;
    }

    public boolean isPaused() {
        return this.getStatus() != TimerStatus.COMPLETED_LEGACY && this.getStatus().getPause() > 0;
    }

    public boolean isPlaying() {
        return !this.isPaused() && this.getStatus() != TimerStatus.COMPLETED_LEGACY;
    }

    public long getEndTime() {
        return this.getStatus() == TimerStatus.COMPLETED_LEGACY ? this.endTime : System.currentTimeMillis();
    }

    public long getStartTime() {
        return this.isStarted() ? startTime : System.currentTimeMillis();
    }

    public long getRealTimeAttack() {
        return this.isCompleted && this != COMPLETED_INSTANCE ? COMPLETED_INSTANCE.getRealTimeAttack() : this.getStatus() == TimerStatus.NONE ? 0 : this.getEndTime() - this.getStartTime() - this.excludedTime;
    }

    public long getInGameTime() { return getInGameTime(true); }

    public long getInGameTime(boolean smooth) {
        if (this.isCompleted && this != COMPLETED_INSTANCE) return COMPLETED_INSTANCE.getInGameTime(smooth);
        if (this.isCoop) return getRealTimeAttack();

        if (this.isGlitched && this.isServerIntegrated && InGameTimerUtils.getServer() != null && SpeedRunIGT.IS_CLIENT_SIDE) {
            Long inGameTime = InGameTimerClientUtils.getPlayerTime();
            if (inGameTime != null) return inGameTime;
        }

        long ms = System.currentTimeMillis();
        return !isStarted() ? 0 :
                (this.getTicks() * 50L) // Tick Based
                        + Math.min(50, smooth && isPlaying() && this.leastTickTime != 0 ? ms - this.leastTickTime : 0) // More smooth timer in playing
                        - this.rebaseIGTime // Subtract Rebased time
                        + this.endIGTTime;
    }

    private static final int RETIME_MINUTES = 17;
    public long getRetimedInGameTime() {
        long base = getInGameTime(false);
        return base + (this.isGlitched || this.isCoop || this.getCategory() != RunCategories.ANY || base >= 60000 * RETIME_MINUTES || this.getRunType() != RunType.RANDOM_SEED ? 0 : this.retimedIGTTime);
    }

    private long firstRenderedTime = 0;
    public void updateFirstInput() {
        if (firstInput.isEmpty() && SpeedRunOption.getOption(SpeedRunOptions.WAITING_FIRST_INPUT).isWorldLoad(this)) {
            firstInput = "First Input: IGT " + timeToStringFormat(getInGameTime(false)) + (leastTickTime == 0 ? "" : " (+ " + (System.currentTimeMillis() - this.leastTickTime) + "ms)") + ", RTA " + timeToStringFormat(getRealTimeAttack());
        }
        if (firstRenderedTime != 0) {
            firstInput = "First World Rendered: " + millisecondToStringFormat(System.currentTimeMillis() - this.firstRenderedTime) + "ms before first input";
        }
    }

    public void updateFirstRendered() {
        if (firstInput.isEmpty() && firstRenderedTime == 0 && SpeedRunOption.getOption(SpeedRunOptions.WAITING_FIRST_INPUT).isFirstInput(this)) {
            firstRenderedTime = System.currentTimeMillis();
        }
    }


    public void tick() {
        if (this.getStatus() == TimerStatus.COMPLETED_LEGACY) return;

        if (isPlaying()) {
            this.activateTicks++;
        }
        this.loggerTicks++;

        long currentTime = System.currentTimeMillis();
        long tickDelays = currentTime - leastTickTime;

        //Rebase time (When a joined world or changed dimension)
        if (leastStartTime != 0 && leastTickTime != 0) {
            rebaseIGTime += MathHelper.clamp((leastStartTime - leastTickTime) * 1.0 / tickDelays, 0, 1) * 50.0;
            leastStartTime = 0;
        }

        this.leastTickTime = currentTime;

        //Logger
        if (tickDelays != currentTime && Math.abs(50 - tickDelays) > 4 && !this.isCoop()) {
            this.freezeLogList.add(new TimerTickLog(activateTicks, loggerTicks, getRealTimeAttack(),
                    tickDelays, getInGameTime(false), this.getStatus() == TimerStatus.IDLE));
            if (this.freezeLogList.size() >= 1000) {
                if (this.isServerIntegrated) {
                    File worldDir = InGameTimerUtils.getTimerLogDir(worldName, "logs");
                    if (worldDir == null) return;
                    File tickFile = new File(worldDir, "igt_freeze" + this.getLogSuffix());
                    String freezeLog = InGameTimerUtils.logListToString(this.freezeLogList, tickFile.exists() ? 0 : this.completeCount);
                    saveManagerThread.submit(() -> {
                        try {
                            FileUtils.writeStringToFile(tickFile, freezeLog, StandardCharsets.UTF_8, true);
                        } catch (IOException e) {
                            e.printStackTrace();
                            SpeedRunIGT.error("Failed to clear freeze logs and saves");
                        }
                    });
                }
                this.freezeLogList.clear();
            }
        }

        if (SpeedRunOption.getOption(SpeedRunOptions.TIMER_DATA_AUTO_SAVE) == SpeedRunOptions.TimerSaveInterval.TICKS) save();
    }

    public void setPause(boolean isPause, String reason) { this.setPause(isPause, TimerStatus.PAUSED, reason); }
    public void setPause(boolean toPause, TimerStatus toStatus, String reason) {
        if (this.getStatus() == TimerStatus.COMPLETED_LEGACY || this.isCoop) return;

        if (toPause) {
            if (this.getStatus().getPause() <= toStatus.getPause()) {
                if (this.getStatus().getPause() < 1 && this.isStarted()) {
                    loggerPausedTime = getRealTimeAttack();
                    prevPauseReason = reason;
                    pauseCount++;
                }
                InGameTimerUtils.CHANGED_OPTIONS.clear();
                InGameTimerUtils.RETIME_IS_WAITING_LOAD = false;
                if (pauseTriggerTick == loggerTicks) tick();
                pauseTriggerTick = loggerTicks;
                this.setStatus(toStatus);

                if (this.isStarted()) {
                    if (SpeedRunOption.getOption(SpeedRunOptions.TIMER_DATA_AUTO_SAVE) == SpeedRunOptions.TimerSaveInterval.PAUSE && status != TimerStatus.LEAVE) save();
                    updateRecordString();
                    writeRecordFile(true);
                }
            }
        } else {
            if (this.isStarted()) {
                long nowTime = getRealTimeAttack();
                long beforeRetime = retimedIGTTime;
                TimerPauseLog.Retime retime = new TimerPauseLog.Retime(0, "");
                if (this.getStatus() == TimerStatus.PAUSED) {
                    if (this.getCategory() == RunCategories.ANY) {
                        if (InGameTimerUtils.RETIME_IS_WAITING_LOAD && InGameTimerUtils.IS_CAN_WAIT_WORLD_LOAD) {
                            retime = new TimerPauseLog.Retime(retimedIGTTime - beforeRetime, "prob. world load pause");
                        } else {
                            if (InGameTimerUtils.CHANGED_OPTIONS.size() > 0) {
                                int options = InGameTimerUtils.CHANGED_OPTIONS.size();
                                retimedIGTTime += Math.max(nowTime - loggerPausedTime - (5000L * options), 0);
                                retime = new TimerPauseLog.Retime(retimedIGTTime - beforeRetime, "changed option" + (options > 1 ? ("s (" + options + ")") : ""));
                                InGameTimerUtils.CHANGED_OPTIONS.clear();
                            } else {
                                retimedIGTTime += nowTime - loggerPausedTime;
                                retime = new TimerPauseLog.Retime(retimedIGTTime - beforeRetime, "");
                            }
                        }
                    }
                }
                if (isPaused()) {
                    this.pauseLogList.add(new TimerPauseLog(prevPauseReason, reason, getInGameTime(false), getRealTimeAttack(), nowTime - loggerPausedTime, pauseCount, retime));
                    if (this.pauseLogList.size() >= 100) {
                        if (this.isServerIntegrated) {
                            File worldDir = InGameTimerUtils.getTimerLogDir(worldName, "logs");
                            if (worldDir == null) return;
                            File pauseFile = new File(worldDir, "igt_timer" + this.getLogSuffix());
                            String pauseLog = InGameTimerUtils.pauseLogListToString(this.pauseLogList, !pauseFile.exists(), pauseFile.exists() ? 0 : this.completeCount);
                            saveManagerThread.submit(() -> {
                                try {
                                    FileUtils.writeStringToFile(pauseFile, pauseLog, StandardCharsets.UTF_8, true);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    SpeedRunIGT.error("Failed to write pause log for clearing logs");
                                }
                            });
                        }
                        this.pauseLogList.clear();
                    }
                    if (this.getCategory() == RunCategories.ALL_ADVANCEMENTS && leaveTime != 0 && leaveTime > startTime) excludedTime = System.currentTimeMillis() - leaveTime;
                    leaveTime = 0;
                }
                if (this.getStatus() == TimerStatus.IDLE && loggerTicks != 0) {
                    leastStartTime = System.currentTimeMillis();
                }
            } else {
                startTime = System.currentTimeMillis();
                if (this.isGlitched) save();
                if (loggerTicks != 0) leastStartTime = startTime;
            }
            this.setStatus(TimerStatus.RUNNING);
        }
    }

    public boolean isResettable() {
        return isResettable || SpeedRunOption.getOption(SpeedRunOptions.TIMER_LIMITLESS_RESET);
    }

    @SuppressWarnings("UnusedReturnValue")
    public boolean tryInsertNewTimeline(String name) {
        return tryInsertNewTimeline(name, true);
    }
    @SuppressWarnings("UnusedReturnValue")
    public boolean tryInsertNewTimeline(String name, boolean canSendPacket) {
        for (TimerTimeline timeline : timelines) {
            if (Objects.equals(timeline.getName(), name)) return false;
        }
        timelines.add(new TimerTimeline(name, getInGameTime(false), getRealTimeAttack()));
        if (canSendPacket && this.isCoop() && SpeedRunIGT.IS_CLIENT_SIDE) TimerPacketUtils.sendClient2ServerPacket(MinecraftClient.getInstance(), new TimerTimelinePacket(name));
        return true;
    }

    public void tryInsertNewAdvancement(String advancementID, String criteriaKey, boolean isAdvancement) {
        TimerAdvancementTracker.AdvancementTrack advancementTrack = advancementsTracker.getOrCreateTrack(advancementID);
        if (criteriaKey == null) {
            if (advancementTrack.isComplete()) return;
            advancementTrack.setComplete(true);
            advancementTrack.setTime(getInGameTime(false), getRealTimeAttack());
        } else {
            advancementTrack.addCriteria(criteriaKey, getInGameTime(false), getRealTimeAttack());
        }
        advancementTrack.setAdvancement(isAdvancement);
    }

    public List<TimerTimeline> getTimelines() {
        return timelines;
    }

    public TimerAdvancementTracker getAdvancementsTracker() {
        return advancementsTracker;
    }

    public boolean isHardcore() {
        return isHardcore;
    }

    public RunType getRunType() {
        return runType;
    }

    public boolean isLegacyIGT() {
        return isGlitched;
    }

    String getLogSuffix() {
        return getLogSuffix(this.completeCount);
    }

    static String getLogSuffix(int count) {
        return (count == 0 ? "" : "_"+count) + ".log";
    }

    public void openedLanIntegratedServer() {
        this.lanOpenedTime = getRealTimeAttack();
    }

    public void checkConditions() {
        if (customCondition != null && customCondition.isDone()) {
            complete();
        }
    }

    public <T> void updateCondition(CategoryCondition.Condition<T> condition, T check) {
        if (condition.isCompleted()) return;
        boolean completed = condition.checkConditionComplete(check);
        if (completed) {
            condition.setCompleted(true);
            tryInsertNewTimeline(condition.getName());
            if (this.isCoop() && SpeedRunIGT.IS_CLIENT_SIDE) TimerPacketUtils.sendClient2ServerPacket(MinecraftClient.getInstance(), new TimerCustomConditionPacket(condition));
        }
    }

    public CategoryCondition getCustomCondition() {
        return customCondition;
    }

    public List<RunPortalPos> getNetherPortalPosList() {
        return lastNetherPortalPos;
    }

    public List<RunPortalPos> getOverWorldPortalPosList() {
        return lastOverWorldPortalPos;
    }

    public void setServerIntegrated(boolean serverIntegrated) {
        isServerIntegrated = serverIntegrated;
    }

    public void setCoop(boolean coop) {
        isCoop = coop;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public UUID getUuid() {
        return uuid;
    }

    public CopyOnWriteArrayList<RunPortalPos> getEndPortalPosList() {
        return endPortalPosList;
    }
}
