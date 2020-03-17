package com.zlongame.ca.map.map.ectype;

import com.zlongame.ca.cfg.CfgMgr;
import com.zlongame.ca.cfg.ConstCfg;
import com.zlongame.ca.cfg.ai.ActionCfg;
import com.zlongame.ca.cfg.ai.PlayCGCfg;
import com.zlongame.ca.cfg.ai.ProfessionCGCfg;
import com.zlongame.ca.cfg.ai.ectypeai.EctypeEnumsCfg;
import com.zlongame.ca.cfg.ai.ectypeai.EctypeEventsCfg;
import com.zlongame.ca.cfg.ectype.*;
import com.zlongame.ca.cfg.error.ErrorCodeCfg;
import com.zlongame.ca.cfg.fight.CampTypeCfg;
import com.zlongame.ca.cfg.fight.PlayerEventCfg;
import com.zlongame.ca.cfg.map.MapTypeCfg;
import com.zlongame.ca.map.MapServer;
import com.zlongame.ca.map.MapUtils;
import com.zlongame.ca.map.agent.*;
import com.zlongame.ca.map.ai.Expression;
import com.zlongame.ca.map.ai.ectype.EctypeAction;
import com.zlongame.ca.map.ai.ectype.EctypeScript;
import com.zlongame.ca.map.ai.ectype.actions.CallEffect;
import com.zlongame.ca.map.aoi.Point;
import com.zlongame.ca.map.event.Event;
import com.zlongame.ca.map.event.normal.DeathEvent;
import com.zlongame.ca.map.event.normal.KillMonsterBonusEvent;
import com.zlongame.ca.map.event.normal.PickupDropItemEvent;
import com.zlongame.ca.map.event.normal.SimpleEvent;
import com.zlongame.ca.map.map.GameMap;
import com.zlongame.ca.map.map.LineMgr;
import com.zlongame.ca.map.map.ectype.ectypedata.data.SettleAccountData;
import com.zlongame.ca.map.map.ectype.ectypedata.data.SettleAccountMgr;
import com.zlongame.ca.map.map.ectype.ectypemanagers.EctypeAreaMgr;
import com.zlongame.ca.map.map.ectype.ectypemanagers.EctypeTeamManager;
import com.zlongame.ca.map.map.ectype.ectypemanagers.ResultMgr;
import com.zlongame.ca.map.map.ectype.ectypemanagers.StatisticMgr;
import com.zlongame.ca.proto.map.*;
import com.zlongame.ca.proto.map.cross.MSwitchToLocalMapProto;
import com.zlongame.ca.proto.module.localmap.MPlayerLeaveEctypeProto;
import com.zlongame.ca.proto.net.IProtocol;
import com.zlongame.ca.share.Log;
import com.zlongame.ca.utils.TimeUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by yl on 2019/10/9.
 */
public abstract class EctypeBase extends GameMap {

    private boolean hasClose;
    private long suspendElapsedTime;

    private static class ScriptBuff {
        Set<Long> players;
        long time;
    }

    //for idgen
    private final static AtomicLong nextId = new AtomicLong();
    private Long remainTime;
    private final static int CLOSE_DELAY = 1000 * 120;
    private final long endTime;
    private final long finalCloseTime;
    private final long startTime;
    private final ResultMgr resultMgr;
    private long suspendOutTime;
    protected EctypeScript script;
    private final StatisticMgr statisticMgr;
    private EctypeAreaMgr areaManager;
    private long beginCountdownTime = ConstCfg.NULL;
    private final Set<Long> deadPlayers = new HashSet<>();
    final HashMap<Long, ActionInfo> currActions = new LinkedHashMap<>();
    private final HashMap<Long, Long> canReviveTime = new HashMap<>();
    private final HashSet<Long> yuanbaoRevivePlayers = new HashSet<>();
    private final Map<Integer, ScriptBuff> scriptBuffPlayers = new HashMap<>();
    private final Map<Long, FakePlayer> fakePlayers = new ConcurrentHashMap<>();
    private final HashMap<Integer, List<Long>> activedTasks = new HashMap<>();
    private final HashMap<Integer, List<Long>> finishedTasks = new HashMap<>();
    private int exceptionTime;
    private static final int EXCEPTION_OUTTIME = 10;
    private boolean ready;
    private long stopPauseTime;

    // 通过脚本添加、 剔除buff 给Player
    public void addScriptBuffToPlayer(int buffId, List<Long> players) {
        ScriptBuff sb = new ScriptBuff();
        sb.players = new HashSet<>(players);
        sb.time = getNow();
        scriptBuffPlayers.put(buffId, sb);
        players.forEach(aid -> {
            Player player = getPlayer(aid);
            if (player != null) {
                player.getBuffMgr().addBuff(player, buffId, 1);
            }
        });
    }

    public void removeScriptBuffFromPlayer(int buffId, List<Long> players) {
        ScriptBuff sb = scriptBuffPlayers.get(buffId);
        if(sb != null) {
            scriptBuffPlayers.remove(buffId);
            players.forEach(aid -> {
                Player player = getPlayer(aid);
                if(player != null) {
                    player.getBuffMgr().remove(buffId);
                }
            });
        }
    }

    private static String genId(int mapModelId, int type) {
        return "Ectype|" + mapModelId + "|" + type + "|" + nextId.getAndIncrement() + "|" + TimeUtil.now();
    }


    protected EctypeBase(int taskId, EctypeBaseCfg ecfg, EctypeCreateParamsProto params, List<EctypeTeamProto> teams) {
        super(genId(ecfg.id, ecfg.type), ecfg.scenename, ecfg.landscapeid);
        this.mapModelId = ecfg.id;
        long createTime = TimeUtil.now();
        this.remainTime = ecfg.elapsedtime * 1000L;
        this.endTime = this.remainTime + createTime + CLOSE_DELAY;
        this.finalCloseTime = this.endTime + TimeUnit.MINUTES.toMillis(30);
        this.startTime = createTime;
        setVisionSize(CfgMgr.roleconfigcfg.ectypeviewsize);
        this.resultMgr = new ResultMgr(this);
        this.statisticMgr = new StatisticMgr(this);
        exceptionTime = 0;
    }

    public abstract EctypeInfomation getInformation();

    public abstract EctypeTeamManager getTeamManager();

    public EctypeAreaMgr getAreaMgr() { return areaManager; }

    public EctypeScript getScript() {
        return script;
    }

    //开关可行走区域
    public void areaOperate(long actionid, int areaId, boolean b, int tid) {
        if (b) {
            areaManager.openArea(areaId, tid, actionid);
        } else {
            areaManager.closeArea(areaId, tid);
        }
    }

    //显隐可行走区域的空气墙
    public void showAirWalls(long actionId, int areaId, boolean b, int tid) {
        if (b) {
            areaManager.openWall(areaId, tid, actionId);
        } else {
            areaManager.closeWall(areaId, tid);
        }
    }

    public static class ActionInfo {
        public Expression action;
        boolean isClientAction;
        boolean suspendMap;
        public int teamMask;
    }

    public boolean isEnd() {
        return !resultMgr.notEnd();
    }

    public void setRemainTime(int remainTime) {
        this.remainTime = remainTime * 1000L;
    }

    public boolean isPvp() {
        return getInformation().isPVPEctype();
    }

    public Set<Long> getTeamMmeberIds(int team, boolean onlyGamers) {
        return new HashSet<>(getTeamManager().getTeamPlayerIds(team, onlyGamers));
    }

    public Set<Long> getTeamMmeberIds(int team) {
        return getTeamMmeberIds(team, false);
    }

    @Override
    public String toString() {
        return String.format("%s{mapid:%s type:%s ectypeid:%s players:%s agents:%s}",
                this.getClass().getSimpleName(), getMapid(), getMapType(), getInformation().getEctypeId(), players.size(), sceneObjMap.size());
    }

    //判断是否还有存活的玩家
    public boolean noLifePlayers(int teamId) {
        Set<Long> memberIds = getTeamMmeberIds(teamId, true);
        List<Sprite> teamMembers = new ArrayList<>();
        memberIds.forEach(memberId -> {
            Sprite sprite = getSprite(memberId);
            if (sprite != null) {
                teamMembers.add(sprite);
            }
        });
        return teamMembers.isEmpty() || teamMembers.stream().allMatch(Sprite::isDead);
    }

    @Override
    public boolean isPlayerValidPoint(Point point) {
        return areaManager.canReach(point);
    }

    public ResultMgr getResultMgr() {
        return resultMgr;
    }

    //暂停整个地图的倒计时， 非暂停的只在客户端实现
    public void beginSuspendCountdown(long remainTime) {
        setSuspend();
        this.beginCountdownTime = now;
        schedule(() -> {
            clearSuspend();
            this.beginCountdownTime = ConstCfg.NULL;
        }, remainTime);
    }

    public StatisticMgr getStatisticMgr() {
        return statisticMgr;
    }

    private List<Long> getAllActions(int team) {
        List<Long> ret = new ArrayList<>();
        currActions.forEach((actionId, actionInfo) -> {
            if ((actionInfo.teamMask & team) == team) {
                ret.add(actionId);
            }
        });
        ret.addAll(areaManager.getTeamWalls(team));
        ret.addAll(areaManager.getTeamAreas(team));
        Long description = getScript().getEnviroment(EctypeEnumsCfg.DESCRIPTION_ACTION);
        if (description != null) {
            ret.add(description);
        }

        return ret;
    }

    protected SEnterEctypeProto genSEnterEctype(Player player) {
        final int teamid = getTeamManager().getPlayerTeamId(player);
        final SEnterEctypeProto re = new SEnterEctypeProto();
        re.id = getMapid();
        re.teamid = teamid;
        re.ectypeid = getInformation().getEctypeId();
        re.remaintime = remainTime + 1000L;
        re.enviroments.intenv.putAll(getScript().getIntEnviroments());
        if (beginCountdownTime != ConstCfg.NULL) {
            re.enviroments.intenv.put("CountDown", (int) (now - beginCountdownTime));
        }
        re.actions.addAll(getAllActions(teamid));
        re.deadcount = statisticMgr.getPlayerDeadCount(player);
        EctypeTeamManager.EctypeTeam teamInfo = getTeamManager().getPlayerTeam(player.getObjId());
        re.teamid = teamInfo.getTeamId();
        re.teams.putAll(getTeamManager().genTeamDetailInfos());
        if (CfgMgr.ectyperecordcfg.get(getInformation().getEctypeId()) != null) {
            re.recordtime = getInformation().getParams().longparams.get(EctypeParamTypeCfg.RECORDTIME);
            re.recordplayers.addAll(getInformation().getParams().recorders.values());
        } else {
            re.recordtime = ConstCfg.NULL;
        }

        re.activedactions.addAll(getActiveTaskList(getTeamManager().getPlayerTeamId(player)));
        re.finishedactions.addAll(getFinishedTaskList(getTeamManager().getPlayerTeamId(player)));
        return re;
    }

    public int getPlayerTotalReviveTime(Sprite player) {
        int cc = getInformation().getReviveInfo(player).revivecount;
        if (cc == ConstCfg.NULL) {
            return ConstCfg.NULL;
        }
        return cc + getTeamManager().getPlayerTeam(player.getObjId()).getExtraReviveTime(player.getObjId());
    }

    private boolean canRevive(Sprite player) {
        final int revievCount = getPlayerTotalReviveTime(player);
        if (revievCount == ConstCfg.NULL) {
            return true;
        } else {
            final int deadCount = statisticMgr.getPlayerDeadCount(player);
            return deadCount <= revievCount;
        }
    }


    private boolean canReviveNow(Sprite player) {
        return getRemainReviveTime(player) <= 0 && canRevive(player);
    }

    private long getRemainReviveTime(Sprite player) {
        return canReviveTime.getOrDefault(player.getObjId(), now) - now - 300L;
    }

    public Point getInitPosition(long roleid) {
        return getInformation().getPlayerEnterInfo(roleid).getInitPosition();
    }

    private com.zlongame.ca.map.aoi.Vector getInitOrient(long roleid) {
        return getInformation().getPlayerEnterInfo(roleid).getInitRotation();
    }

    public void doRevive(Sprite player) {
        if (player.getMap() == null || !player.getMap().isEctype()) {
            Log.debug("dead player map is null");
            return;
        }
        canReviveTime.remove(player.getObjId());
        EctypeRevivePosCfg revivePosInfo = getInformation().getReviveInfo(player).reviveposinfo;
        switch (revivePosInfo.getTypeId()) {
            case CurPosCfg.TYPEID: {
                player.reviveAtCurPosition();
                break;
            }
            case BornPosCfg.TYPEID: {
                player.revive(getInitPosition(player.getObjId()), getInitOrient(player.getObjId()));
                break;
            }
            case SpecificPositionsCfg.TYPEID: {
                SpecificPositionsCfg possInfo = (SpecificPositionsCfg) revivePosInfo;
                Point revivePoint = MapUtils.getNearestPointByConfigList(possInfo.pos, player.getPosition());
                player.revive(revivePoint == null ? player.getPosition() : revivePoint, player.getDirection());
                break;
            }
            case NearestPosCfg.TYPEID: {
                NearestPosCfg np = (NearestPosCfg) revivePosInfo;
                Point point = MapUtils.getNearestPointByConfigList(np.positions, player.getPosition());
                Point extra = getTeamManager().getPlayerTeam(player.getObjId()).findNearestExtraRevivePoint(player.getPosition());
                if (point == null && extra == null) {
                    player.revive(player.getPosition(), player.getDirection());
                } else if (point == null) {
                    player.revive(extra, player.getDirection());
                } else if (extra == null) {
                    player.revive(point, player.getDirection());
                } else {
                    player.revive(extra.getDistance(player.getPosition()) < point.getDistance(player.getPosition()) ? extra : point, player.getDirection());
                }
                break;
            }

            default: {
                Log.error("unknown revivetyp:");
            }
        }
        deadPlayers.remove(player.getObjId());
    }

    public void yuanbaoRevive(Player player) {
        if (player.isDead() && !yuanbaoRevivePlayers.contains(player.getObjId()) && getRemainReviveTime(player) <= 0) {
            EctypeReviveInfoCfg reviveInfoCfg = getReviveInfo(player.getObjId());
            if(reviveInfoCfg.extrarevive.getTypeId() == YuanBaoReviveCfg.TYPEID) {
                YuanBaoReviveCfg ybr = (YuanBaoReviveCfg)(reviveInfoCfg.extrarevive);
                Integer cost = ybr.cost.get(getStatisticMgr().getPlayerExtraYuanbaoRevive(player.getObjId()));
                if (cost != null) {
                    MYuanBaoReviveProto msg = new MYuanBaoReviveProto(player.getObjId(), getMapid(), cost);
                    sendMsgToXdb(msg);
                    yuanbaoRevivePlayers.add(player.getObjId());//防止多发多扣
                }
            }
        }
    }
    public void onVerifyYuanbaoRevive(long roleId, ErrorCodeCfg err) {
        Player player = getPlayer(roleId);
        if (player != null && player.isDead() && yuanbaoRevivePlayers.contains(roleId)) {
            yuanbaoRevivePlayers.remove(roleId);
            if (err != ErrorCodeCfg.OK) {
                player.sendError(err);
            } else {
                getStatisticMgr().onPlayerExtraYuanbaoRevive(roleId);
                doRevive(player);
                player.sendClient(new SYuanBaoReviveProto(getStatisticMgr().getPlayerExtraYuanbaoRevive(roleId)));
            }
        }
    }

    @Override
    public int getMapType() {
        return MapTypeCfg.ECTYPE;
    }

    @Override
    public void loadClientMap(Player player) {
        player.sendClient(genSEnterEctype(player));
    }

    @Override
    public void onPlayerEnter(Player player) {
        statisticMgr.onPlayerEnter(player);
        if (getInformation().isBattleground() && !resultMgr.notEnd()) {
            leaveEctype(player);
            return;
        }

        if (deadPlayers.contains(player.getObjId())) {
            if (getInformation().getReviveType() == EctypeReviveTypeCfg.COUNT_DOWN) {//&& !canReviveNow(player)) {
                if (canReviveNow(player)) {
                    doRevive(player);
                } else {
                    player.setDead();
                }
            }
            if (getInformation().getReviveType() == EctypeReviveTypeCfg.NORMAL) {
                player.setDead();
            }
        }
        player.setCamp(getTeamManager().getPlayerTeam(player.getObjId()).getCamp());
    }

    @Override
    public void onPlayerLeave(Player player) {
        if (resultMgr.notEnd()) {
            if (players.isEmpty() && getInformation().getTypeInfo().emptysuspend) {
                resultMgr.onForceLeave();
            }
        }
//        if (getInformation().isCityWar()) {
//            sendMsgToXdb(new MPlayerCityWarEventProto(information.getEctypeId(), getMapid(), player.getObjId(), false));
//        }
        getScript().trigger(EctypeEventsCfg.PLAYER_LEAVE, player);
        player.trigger(PlayerEventCfg.LEAVE_ECTYPE, new SimpleEvent(player));
//        player.setCamp(CampTypeCfg.PLAYER);
    }

    private boolean isReady() {
        return ready || getInformation().getTypeInfo().autoready;
    }

    @Override
    public void updatemap(long now) {
        if (now > finalCloseTime) {
            close();
            return;
        }

        updateEctype(now);

        if(hasClose) return;

        if(!isEnd()) {
            if(!isSuspend()) {
                try {
                    if(isReady()) {
                        if(!getScript().isStart()) {
                            getScript().start();
                        }
                        getScript().update(now);
                    }
                } catch (Exception e) {
                    if ( ++exceptionTime > EXCEPTION_OUTTIME) {
                        broadcast(new SEctypeExceptionProto(getInformation().getEctypeId()));
                        close();
                    }
                    throw e;
                }
                statisticMgr.update(now);
                remainTime -= getDeltaTime();
                suspendElapsedTime = 0;
                if(remainTime < 0) {
                    resultMgr.onTimeLimitExceed();
                }
            } else {
                if(players.isEmpty() && getInformation().getTypeInfo().emptysuspend) {
                    suspendElapsedTime += getDeltaTime();
                    if(suspendElapsedTime > suspendOutTime) {
                        close();
                    }
                }
            }
        } else {
            if((players.isEmpty() || (endTime != ConstCfg.NULL && now > endTime)) && !hasClose) {
                close();
            }
        }
    }

    private void updateEctype(long now) {
        this.deltaTime = now - this.now;
        this.now = now;
        for (int i = 0; i < 50; i++) {
            Runnable task = tasks.poll();
            if (task != null) {
                task.run();
            } else {
                break;
            }
        }
        scheduler.update(now);
        updatePlayer(now);
        if (!isSuspend()) {
            updateNormal(now);
            mapObjRefresher.update(now);
        }
    }

    public void pause(float seconds) {
        this.stopPauseTime = now + (long) (seconds * TimeUtil.SECOND);
    }

    @Override
    public boolean isSuspend() {
        return (issuspend || isEnd() || (players.isEmpty() && getInformation().getTypeInfo().emptysuspend) || now < stopPauseTime);
    }

    @Override
    public void revivePlayer(Player player) {
        if (!player.isDead()) {
            return;
        }

        if (canReviveNow(player)) {
            doRevive(player);
        } else {
            Log.error("player objId = {} can not revive,deadcount = {},totalcount = {},deadtime = {},now = {},countdown = {}",
                    player.getObjId(), statisticMgr.getPlayerDeadCount(player), getInformation().getReviveInfo(player).revivecount, statisticMgr.getPlayerDeadTime(player), getNow(), getInformation().getReviveInfo(player).countdown);
        }
    }

    public void endEctype(boolean showSettlement, SettleAccountData data) {
        endEctype(showSettlement, data, EctypeEndReasonCfg.COMPLETE);
    }

    public void endEctype(boolean showSettlement, SettleAccountData data, int reason) {
        if (resultMgr.notEnd()) {
            setSuspend();
            statisticMgr.onEndEctype();
            if (data == null) {
                data = new SettleAccountData();
            }
            data.remainTime = remainTime;
            data.totalTime = getInformation().getConfig().elapsedtime * 1000L;
            resultMgr.endEctype(showSettlement, data, reason);
        }
    }


    public Map<Integer, EctypeTeamManager.EctypeTeam> getTeams() {
        return getTeamManager().getTeams();
    }

    public void leaveEctype(Player player) {
        getTeamManager().onPlayerLeave(player);
        onLeave(player);
    }

    protected void onLeave(Player player) {
        if(player.isDead()) {
            player.reviveAtCurPosition();
        }
        player.getSkillMgr().interruptCurSkill(true);
        MPlayerLeaveEctypeProto msg = new MPlayerLeaveEctypeProto();
        msg.ectypeid = getInformation().getEctypeId();
        msg.status = isEnd() ? 1 : 2;
        msg.result = getResultMgr().isSuccCompleted(player);
        msg.deadcount = getStatisticMgr().getPlayerDeadCount(player);
        msg.duration = TimeUtil.now() - startTime;
        player.sendXdb(msg);
        player.setCamp(CampTypeCfg.PLAYER);

        if(MapServer.isCrossServer()) {
            unRegister(player);
            sendMsgToXdb(player.getRoleid(), new MSwitchToLocalMapProto(
                    getInformation().getEctypeId(),
                    player.getPosition().toProto(),
                    player.getDirection().toProto()
            ));
            LineMgr.removePlayer(player.getObjId());
        } else {
            player.returnWorld();
        }
    }

    @Override
    public void clear() {
        super.clear();
        resultMgr.onReleaseHoldNpc();
    }

    @Override
    public void close() {
        try {
            if (resultMgr.notEnd()) {
                SettleAccountData data = getScript().getEnviroment(EctypeEnumsCfg.ECTYPE_RESULT);
                if (data == null) {
                    data = SettleAccountMgr.getDefaultSettleAccountDataByEctype(this);
                    data.lose = true;
                }
                endEctype(false, data);
            }
            hasClose = true;
            foreachAllPlayer(this::leaveEctype);
            if (resultMgr.notEnd()) {
                resultMgr.onNoEndClose();
            }
        } finally {
            super.close();
        }
    }

    public Sprite getPlayerOrFakePlayer(long aid) {
        Sprite sprite = getSprite(aid);
        if (sprite != null && sprite.isPlayerOrFakePlayer()) {
            return sprite;
        }
        return null;
    }

    public FakePlayer createFakePlayer(int monsterId) {
        FakePlayer fakePlayer = new FakePlayer(CfgMgr.monstercfg.get(monsterId));
        fakePlayers.put(fakePlayer.getObjId(), fakePlayer);
        return fakePlayer;
    }

    public FakePlayer createFakePlayer(int monsterId, int level) {
        FakePlayer fakePlayer = new FakePlayer(CfgMgr.monstercfg.get(monsterId), level);
        fakePlayers.put(fakePlayer.getObjId(), fakePlayer);
        return fakePlayer;
    }

    public boolean isTeamPlayerOrFakePlayers(long aid) {
        return fakePlayers.containsKey(aid) || players.contains(aid);
    }

    public boolean hasKey(Player player) {
        Integer val = getScript().getIntEnviroments().get("key");
        return val != null && val > 0;
    }

    private void setReady(boolean b) {
        ready = b;
    }

    private void broadcastNotContextMsg(IProtocol proto) {
        broadcast(proto);
    }

    public void broadcastNotContextMsgToTeam(IProtocol proto, int teamMask) { // only all
        getTeamManager().foreachTeam(teamMask, (teamId) -> {
            getTeamManager().broadcastToTeam(teamId, proto);
        });
    }

    public void setIntegerEnviroment(String key, int value) {
        getScript().setIntEnviroment(key,value);
        broadcastNotContextMsg(new SChangeIntEnviromentProto(key, value));
    }

    public void broadcastToTeam(Player player, IProtocol protocol) {
        broadcastToTeam(getTeamManager().getPlayerTeamId(player), protocol);
    }

    public void broadcastToTeam(int teamId, IProtocol protocol) {
        getTeamManager().broadcastToTeam(teamId, protocol);
    }


    public List<Sprite> getTeamFighters(int teamId) {
        return getTeamFighters(teamId, false);
    }

    public List<Sprite> getTeamFighters(int teamid, boolean onlyPlayer) {
        if (onlyPlayer) {
            return getTeamManager().getTeamPlayers(teamid, true);
        }
        return getTeamManager().getTeamSprites(teamid);
    }

    public boolean isNotEmptyTeam(int teamId) {
        return !getTeamManager().isEmptyTeam(teamId);
    }

    public List<SceneObj> openController(int id) {
        return controllerMgr.open(id, MonsterCreateType.ECTYPE);
    }

    public void closeController(int id, int reason) {
        controllerMgr.close(id);
    }

    public void openAllControllers() {
        controllerMgr.openAll(MonsterCreateType.ECTYPE);
    }

    public List<Long> getMonstersByControllerId(int id) {
        return controllerMgr.getMonstersByControllerId(id);
    }

    private boolean isTimeLimitClientAction(Object action) {
        Expression expr = (Expression) action;
        if (expr instanceof CallEffect) {
            return false;
        }
        return true;
    }


    public void beginAction(long actionid, Expression action, boolean isClientAction, boolean suspendMap, int teamMask) {
        Log.debug("beginAction. actionid:{} action:{} isclientaction:{} suspendmap:{}", actionid, action, isClientAction, suspendMap);
        final ActionInfo a = new ActionInfo();
        if (action instanceof EctypeAction) {
            ActionCfg eaction = ((EctypeAction) action).getConfig();
            if (eaction.getTypeId() == PlayCGCfg.TYPEID) {
                PlayCGCfg playCG = (PlayCGCfg) eaction;
                getTeamManager().getTeamPlayers(teamMask, true).forEach((player) -> {
                    getStatisticMgr().recordCG(player.getObjId(), playCG.name);
                });
            }
            if (eaction.getTypeId() == ProfessionCGCfg.TYPEID) {
                ProfessionCGCfg professionCG = (ProfessionCGCfg) eaction;
                getTeamManager().getTeamPlayers(teamMask, true).forEach((sprite) -> {
                    Player player = (Player) sprite;
                    getStatisticMgr().recordCG(player.getObjId(), professionCG.professioncgs.get(player.getProfession()));
                });
            }
        }
        a.action = action;
        a.isClientAction = isClientAction;
        a.suspendMap = suspendMap;
        a.teamMask = teamMask;
        currActions.put(actionid, a);
        broadcastNotContextMsgToTeam(new SActionBeginProto(actionid), teamMask);
        if (suspendMap) {
            setSuspend();
        }
        if (isClientAction && isTimeLimitClientAction(action)) {
            schedule(() -> {
                if (currActions.containsKey(actionid)) {
                    Log.error("story:{} not confirm client actionid:{}", this, actionid);
                    endClientAction(actionid);
                }
            }, 300 * 1000);
        }
    }

    public void endClientAction(long actionid) {
        final ActionInfo a = currActions.get(actionid);
        if (a != null) {
            Log.debug("end client action id = {}", actionid);
            broadcastNotContextMsgToTeam(new SActionEndProto(actionid), a.teamMask);
            currActions.remove(actionid);
            if (a.suspendMap) {
                Log.debug("clear Suspend in end client action id = {}", actionid);
                clearSuspend();
            }
        }
    }

    public void beginNotEndClientAction(long actionid, int teamMask) {
        broadcastNotContextMsgToTeam(new SActionBeginProto(actionid), teamMask);
    }

    public int getIntegerEnviroment(String key) {
        return getScript().getIntegerEnviroment(key);
    }

    public boolean isClientActionEnd(long actionid) {
        return !currActions.containsKey(actionid);
    }

    @Override
    public void onSceneObjEnter(SceneObj sceneObj) {
        super.onSceneObjEnter(sceneObj);
        if (sceneObj.isMonster()) {
            getScript().trigger(EctypeEventsCfg.MONSTER_ENTER, sceneObj);
        }
        if (sceneObj.isMine()) {
            getScript().trigger(EctypeEventsCfg.MINE_ENTER, sceneObj);
        }
        if (sceneObj.isRune()) {
            getScript().trigger(EctypeEventsCfg.RUNE_ENTER, sceneObj);
        }
    }

    @Override
    public void registerSprite(SceneObj sceneObj) {
        if (sceneObj.isSprite()) {
            Sprite sprite = (Sprite) sceneObj;
            super.registerSprite(sprite, getInitPosition(sprite.getOwner().getObjId()), getInitOrient(sprite.getOwner().getObjId()));
        } else {
            super.registerSprite(sceneObj, getInitPosition(sceneObj.getObjId()), getInitOrient(sceneObj.getObjId()));
        }
    }

    @Override
    public void onPlayerReady(Player player) {
        Log.debug("on player ready {}", player.getObjId());
        if (resultMgr.notEnd() && !this.isReady()) {
            setReady(true);
            clearSuspend();
        }
        statisticMgr.onPlayerReEnter(player);
        if (this.isEnd()) {
            resultMgr.sendEndInfo(player);
        }
        if (player.isDead()) {
            final int deadCount = statisticMgr.getPlayerDeadCount(player);
            final int totalReviveCount = getPlayerTotalReviveTime(player);
            final long deadTime = statisticMgr.getPlayerDeadTime(player);
            final long remainReviveSeconds = now - deadTime - getInformation().getReviveInfo(player).countdown * 1000L;
            if (remainReviveSeconds > 0 && getInformation().getReviveType() == EctypeReviveTypeCfg.COUNT_DOWN && canRevive(player)) {
                doRevive(player);
            } else {
                player.sendClient(new SDeadCountProto(totalReviveCount, deadCount, getRemainReviveTime(player)));
            }
        }
        SSyncEctypeActionsProto msg = new SSyncEctypeActionsProto();
        int teamId = getTeamManager().getPlayerTeamId(player.getObjId());
        msg.actions.addAll(getAllActions(teamId));
        msg.areas.addAll(getAreaMgr().getTeamAreasRegionIds(teamId));
        msg.walls.addAll(getAreaMgr().getTeamWallsRegionIds(teamId));
        player.sendClient(msg);

        if (getScript() != null) {
            getScript().trigger(EctypeEventsCfg.PLAYER_ENTER, player);
        }
    }

    public void playerEnter(Player player, int teamNo) { // teamNo = 1,2,3,...
        getTeamManager().onPlayerEnter(player.getObjId(), teamNo);
        getInformation().genPlayerInitInfo(player.getObjId());
        registerSprite(player);
    }

    public boolean noLeftPlayers(int teamId) {
        List<Sprite> teamFighters = getTeamFighters(teamId);
        boolean ret = teamFighters.isEmpty() || (teamFighters.stream().allMatch(Sprite::isDead) && teamFighters.stream().noneMatch(this::canRevive));
        return ret;
    }

    public void onDeath(SceneObj sceneObj, Event event)
    {
        final DeathEvent dp = (DeathEvent) event;
        final Sprite defencer = dp.defencer;
        long defenerId = defencer.getObjId();
        if (defencer.isPlayerOrFakePlayer() && isTeamPlayerOrFakePlayers(defenerId)) {
            statisticMgr.onPlayerDeath(sceneObj, event);
            deadPlayers.add(defencer.getObjId());
            final int totalReviveCount = getPlayerTotalReviveTime(defencer);
            final int deadCount = statisticMgr.getPlayerDeadCount(defencer);
            canReviveTime.put(defenerId, statisticMgr.getPlayerDeadTime(defencer) + getInformation().getReviveInfo(defencer).countdown * TimeUtil.SECOND + getTeamManager().getPlayerTeam(defencer.getObjId()).getPunishReviveTime());
            if (defencer.isPlayer()) {
                defencer.asPlayer().sendClient(new SDeadCountProto(totalReviveCount, deadCount, getRemainReviveTime(defencer)));
            }

            List<Sprite> teamPlayers = getTeamManager().getTeamPlayers(getTeamManager().getPlayerTeamId(defenerId));
            if (teamPlayers.stream().allMatch(Sprite::isDead) && teamPlayers.stream().noneMatch(this::canRevive) && !getInformation().isBattleground() && getInformation().isSingleTeamEctype()) {
                resultMgr.onDeadCountExceed(defencer);
                return;
            }
            if (getInformation().getReviveType() == EctypeReviveTypeCfg.COUNT_DOWN) {
                if (canRevive(defencer)) {
                    if (defencer.isPlayer()) {// player
                        schedule(() -> {
                            revivePlayer(defencer.asPlayer());
                        }, getInformation().getReviveInfo(defencer).countdown * TimeUtil.SECOND + getTeamManager().getPlayerTeam(defencer.getObjId()).getPunishReviveTime());
                    } else {        // fake player
                        schedule(() -> doRevive(defencer), getInformation().getReviveInfo(defencer).countdown * TimeUtil.SECOND + getTeamManager().getPlayerTeam(defencer.getObjId()).getPunishReviveTime());
                    }
                }
            }

            if (getInformation().getReviveType() == EctypeReviveTypeCfg.NORMAL) {
                if (defencer.isFakePlayer() && canRevive(defencer)) {
                    schedule(() -> doRevive(defencer), getInformation().getReviveInfo(defencer).countdown * TimeUtil.SECOND + getTeamManager().getPlayerTeam(defencer.getObjId()).getPunishReviveTime());
                }
            }
            getScript().trigger(EctypeEventsCfg.PLAYER_DEAD, defencer);
        }
        else if(dp.defencer.isMonster()) {
            if(getInformation().isSingleTeamEctype()) {
                MKillTaskMonsterProto msg = new MKillTaskMonsterProto();
                msg.roles.addAll(getTeamManager().getTeamPlayerIds(getTeamManager().getALL_TEAMS()));
                msg.monsterid = dp.defencer.getModelId();
                sendMsgToXdb(msg);
            }

        }
    }

    @Override
    public void init() {
        super.init();
        getTeamManager().init();
        getStatisticMgr().init();
        getInformation().init();
        this.areaManager = new EctypeAreaMgr(getInformation().getRegionsetId());
        suspendOutTime = Math.min(TimeUnit.MINUTES.toMillis(5), getInformation().getConfig().suspendouttime * 1000L);

        addListener(PlayerEventCfg.DEATH, this::onDeath);

        addListener(PlayerEventCfg.PICKUP_DROP_ITEM, ((sceneObj, event) -> {
            PickupDropItemEvent evt = event.cast();
            statisticMgr.onPlayerBonus(evt.player.getObjId(), evt.bonus);
        }));

        addListener(PlayerEventCfg.MONSTER_KILL_BONUS, ((sceneObj, event) -> {
            KillMonsterBonusEvent evt = event.cast();
            statisticMgr.onPlayerBonus(evt.player.getObjId(), evt.bonusEntity.toProto());
        }));

        fakePlayers.forEach((aid, fp) -> {
            fp.setCamp(getTeamManager().getPlayerTeam(fp.getObjId()).getCamp());
            registerSprite(fp, getInitPosition(aid), getInitOrient(aid));
        });
        if (getInformation().getTypeInfo().emptysuspend) {
            setSuspend();
        }
        initOver();
    }

    public EctypeReviveInfoCfg getReviveInfo(long aid) {
        return getInformation().getReviveInfo(getTeamManager().getPlayerTeamId(aid));
    }

    protected void addActivedTask(int teamId, long actionid) {
        getActiveTaskList(teamId).add(actionid);
        broadcastToTeam(teamId, new SActiveEctypeActionProto(actionid));
    }

    protected void addFinishedTask(int teamId, long actionid) {
        getFinishedTaskList(teamId).add(actionid);
        broadcastToTeam(teamId, new SFinishEctypeActionProto(actionid));
    }

    private List<Long> getActiveTaskList(int teamId) {
        return activedTasks.computeIfAbsent(teamId, k -> new ArrayList<>());
    }

    private List<Long> getFinishedTaskList(int teamId) {
        return finishedTasks.computeIfAbsent(teamId, k -> new ArrayList<>());
    }
}
