package com.zlongame.ca.map.ai.fighter;

import com.zlongame.ca.cfg.CfgMgr;
import com.zlongame.ca.cfg.ConstCfg;
import com.zlongame.ca.cfg.ai.fighterai.BornPosCfg;
import com.zlongame.ca.cfg.ai.fighterai.CurrentPosCfg;
import com.zlongame.ca.cfg.ai.fighterai.CustomAICfg;
import com.zlongame.ca.cfg.ai.fighterai.DefaultAICfg;
import com.zlongame.ca.cfg.ai.fighterai.FighterEnviromentNamesCfg;
import com.zlongame.ca.cfg.ai.fighterai.FighterEventsCfg;
import com.zlongame.ca.cfg.ai.fighterai.FighterParamsCfg;
import com.zlongame.ca.cfg.ai.fighterai.HalfCustomAICfg;
import com.zlongame.ca.cfg.ai.fighterai.NullAICfg;
import com.zlongame.ca.cfg.ai.fighterai.OriginPositionCfg;
import com.zlongame.ca.cfg.ai.fighterai.PatrolInfoCfg;
import com.zlongame.ca.cfg.ai.fighterai.PetAICfg;
import com.zlongame.ca.cfg.ai.fighterai.RelativeToLeaderCfg;
import com.zlongame.ca.cfg.ai.fighterai.RetinueAICfg;
import com.zlongame.ca.cfg.ai.fighterai.aiconfigCfg;
import com.zlongame.ca.cfg.fight.PlayerEventCfg;
import com.zlongame.ca.map.agent.SceneObj;
import com.zlongame.ca.map.agent.Sprite;
import com.zlongame.ca.map.agent.SpriteComponents;
import com.zlongame.ca.map.aoi.Point;
import com.zlongame.ca.map.aoi.Vector;
import com.zlongame.ca.map.event.Event;
import com.zlongame.ca.map.event.Listener;
import com.zlongame.ca.map.event.normal.BeAttackEvent;
import com.zlongame.ca.map.event.normal.DamageEvent;
import com.zlongame.ca.map.event.normal.DeathEvent;
import com.zlongame.ca.map.event.normal.SimpleEvent;
import com.zlongame.ca.proto.map.SSyncBattleProto;


/**
 * Created by yl on 2017/2/21.
 */
public class FighterAI implements SpriteComponents {

    protected static class FighterAIFrameData {
        Point originPosition;
        boolean inTraceRange;
        boolean inGuardRange;
        boolean inTransmitRange;
    }

    //region Members
    protected Sprite self;
    private boolean positive;
    private float guardRange;
    private float traceRange;
    private Point originPosition;
    protected FighterScript ai;
    protected boolean isFighting;
    private aiconfigCfg info;
    private PatrolInfoCfg patrolInfo;
    private boolean halt;
    private Point fightPosition;
    private final OriginPositionCfg originPosInfo;
    private long ownerId;
    private FighterHatredMgr hatredMgr;
    protected FighterTargetMgr targetMgr;

    protected FighterAIFrameData frameData;  // 避免一帧计算多次

    public Sprite getHost() {
        return self;
    }

    public Sprite getOwner() {
        return ownerId == ConstCfg.NULL  ? self : self.getMap().getSprite(ownerId);
    }

    public void setOwner(Sprite owner) {
        ownerId = owner == null ? ConstCfg.NULL : owner.getObjId();
    }

    public boolean hasOwner() {
        return ownerId != ConstCfg.NULL;
    }

    public void setOriginPosition(Point p) {
        originPosition = p;
    }

    protected void calcOriginPosition() {
        switch (originPosInfo.getTypeId()) {
            case BornPosCfg.TYPEID:
                frameData.originPosition = originPosition;
                break;
            case CurrentPosCfg.TYPEID:
                frameData.originPosition = isFighting ? fightPosition : self.getPosition();
                break;
            case RelativeToLeaderCfg.TYPEID:
                if(hasOwner()) {
                    RelativeToLeaderCfg posCfg = (RelativeToLeaderCfg)originPosInfo;
                    Sprite owner = self.getOwner();
                    if(owner != null) {
                        Vector dir = owner.getDirection().rotate(posCfg.angle);
                        Point ret = owner.getPosition().directionMove(dir, posCfg.distance);
                        if (!self.getMap().isValidPoint(ret)) {
                            self.getMap().searchValidPoint(ret, 5);
                        }
                        if (!self.getMap().isValidPoint(ret)) {
                            ret = owner.getPosition();
                        }
                        frameData.originPosition = ret;
                    } else {
                        frameData.originPosition = originPosition;
                    }

                } else {
                    frameData.originPosition = originPosition;
                }
                break;
            default:
                throw new RuntimeException("unknown origin position type : " + originPosInfo.getClass().getName());
        }
    }

    protected void calcInGuardRange() {
        frameData.inGuardRange = getOriginPosition().getDistance(self.getPosition()) < getGuardRange();
    }

    protected void calcInTraceRange() {
        frameData.inGuardRange = getOriginPosition().getDistance(self.getPosition()) < getTraceRange();
    }

    protected void calcFrameParameters() {
        calcOriginPosition();
        calcInGuardRange();
        calcInTraceRange();
    }

    public Point getOriginPosition() {
        if(frameData.originPosition == null) {
            calcFrameParameters();
        }
        return frameData.originPosition;

    }

    public void fight(boolean b) {
        if (isFighting ^ b) {
            isFighting = b;
            //进入战斗的移动速度和动作要变成跑的
            self.setRun(b);
            if (b) {
                fightPosition = self.getPosition();
                self.broadcast(new SSyncBattleProto(self.getObjId(), true));
                ai.trigger(FighterEventsCfg.ENTER_FIGHT,self);
            } else {
                hatredMgr.clear();
                fightPosition = null;
                self.getPursuitManager().exit();
                self.broadcast(new SSyncBattleProto(self.getObjId(), false));
                ai.trigger(FighterEventsCfg.EXIT_FIGHT,self);
                removeData(FighterEnviromentNamesCfg.FIGHTER_TARGET);
            }
        }
    }

    public boolean isFighting() {
        return isFighting;
    }

    public void addHatred(long aid, float hatred) {
        Sprite fighter = self.getMap().getSprite(aid);
        if (targetMgr.isValidTarget(fighter)) {
            hatredMgr.addHatred(aid,hatred);
        }
    }

    public boolean inGuardRange() {
        return frameData.inGuardRange;
    }

    public boolean inGuardRange(Point point) {
        return getOriginPosition().getDistance(point) < getGuardRange();
    }

    public boolean inTraceRange(Point point) {
        return getOriginPosition().getDistance(point) < getTraceRange();
    }

    public boolean inRange(float distance) {
        return getOriginPosition().getDistance(self.getPosition()) < distance;
    }

    public float getTraceRange() {
        return traceRange;
    }

    public boolean inTraceRange() {
        return self.getPosition().getDistance(getOriginPosition()) <= getTraceRange();
    }

    public boolean isPositive() {
        return positive;
    }

    @Override
    public void update(long now) {

        if (ai == null) {
            return;
        }
        if (halt) {
            return;
        }
        if (!isFighting && self.getDeployment() != null && !self.getDeployment().anyPlayersInView()) {
            return;
        }
        calcFrameParameters();
        targetMgr.update();
        if (!self.isDead() && !self.getMap().isSuspend()) {//&& self.getMap().isValidPoint(self.getPosition())) { // 8.20 + 在合理的位置才会运行AI，位置不合
            ai.update(now);
        }
    }

    public float getGuardRange() {
        return guardRange;
    }
    //endregion public Functions

    //region private functions

    @Override
    public void onDead() {
        ai.reset();
    }

    @Override
    public void reset() {
        ai.reset();
        if (info.resetrecover) {
            self.getPropertyMgr().setHpMpFull();
        }
        halt = false;
        fight(false);
        self.getPursuitManager().exit();
        self.setRun(false);
        self.trigger(PlayerEventCfg.AI_RESET, null);
        targetMgr.reset();
    }

    public void halt() {
        halt = true;
    }

    public void restart() {
        halt = false;
    }

    //endregion

    //region Construct

    ;
    private Listener onBeHarmfulBuff = this::onBeHarmfulBuff;
    private Listener onBeDamage = this::onBeDamage;
    private Listener onBeAttack = this::onBeAttack;
    private Listener onDeath = this::onDeath;
    private Listener onCampChanged = this::onCampChanged;
    private Listener onInvincibleFinished = this::onInvincibleFinished;

    private void onInvincibleFinished(SceneObj sceneObj, Event event) {
        targetMgr.onInvincibleFinished((Sprite)sceneObj);
    }

    private void onCampChanged(SceneObj sceneObj, Event event) {
        ai.trigger(FighterEventsCfg.CAMP_CHANGED, sceneObj);
    }

    public void clear() {
        self.removeListener(PlayerEventCfg.BE_HARMFUL_BUFF, onBeHarmfulBuff);
        self.removeListener(PlayerEventCfg.BE_DAMAGE, onBeDamage);
        self.removeListener(PlayerEventCfg.BE_ATTACK, onBeAttack);
        self.removeListener(PlayerEventCfg.DEATH, onDeath);
        self.removeListener(PlayerEventCfg.CAMP_CHANGED, onCampChanged);
        self.removeListener(PlayerEventCfg.INVINCIBLE_FINISHED, onInvincibleFinished);
        ai = null;
        originPosition = null;
    }

    public void init(Sprite fighter, boolean positive, float guardRange, float traceRange) {
        fighter.registerComponents(this);
        this.positive = positive;
        this.guardRange = guardRange;
        this.traceRange = traceRange;
        self = fighter;
        hatredMgr = new FighterHatredMgr(self);
        originPosition = fighter.getPosition();
        switch (info.aiinfo.getTypeId()) {
            case DefaultAICfg.TYPEID: {
                ai = new FighterScript(CfgMgr.fighteraicfg.get(FighterParamsCfg.DEFAULT_AI_ID), self);
                break;
            }
            case HalfCustomAICfg.TYPEID: {
                ai = new FighterScript(CfgMgr.fighteraicfg.get(FighterParamsCfg.HALF_CUSTOM_AI_ID),self);
                break;
            }
            case CustomAICfg.TYPEID: {
                final CustomAICfg cai = (CustomAICfg) info.aiinfo;
                ai = new FighterScript(CfgMgr.fighteraicfg.get(cai.aiid), self);
                break;
            }
            case NullAICfg.TYPEID: {
                ai = null;
                break;
            }
            case PetAICfg.TYPEID:{
                ai = new FighterScript(CfgMgr.fighteraicfg.get(FighterParamsCfg.DEFAULT_PET_AI), self);
                break;
            }
            case RetinueAICfg.TYPEID:{
                final RetinueAICfg rai = (RetinueAICfg) info.aiinfo;
                ai =  new FighterScript(CfgMgr.fighteraicfg.get(rai.aiid), self);
                break;
            }
            default: {
                throw new RuntimeException("unknown ai type");
            }
        }

        self.addListener(PlayerEventCfg.BE_HARMFUL_BUFF, onBeHarmfulBuff);
        self.addListener(PlayerEventCfg.BE_DAMAGE, onBeDamage);
        self.addListener(PlayerEventCfg.BE_ATTACK, onBeAttack);
        self.addListener(PlayerEventCfg.DEATH, onDeath);
        self.addListener(PlayerEventCfg.CAMP_CHANGED, onCampChanged);
        self.addListener(PlayerEventCfg.INVINCIBLE_FINISHED, onInvincibleFinished);
        ai.start();
        targetMgr = new FighterTargetMgr(self, this);
        targetMgr.init();
        reset();

        ownerId = ConstCfg.NULL;
        initFrameData();
    }

    private void onBeHarmfulBuff(Object obj, Object ctx) {
        final SimpleEvent se = (SimpleEvent) ctx;
        Sprite attacker = (Sprite) se.object;
        if (self.getAI().targetMgr.isValidTarget(attacker.getOwner())) {
            self.getAI().trigger(FighterEventsCfg.BE_ATTACK, attacker);
        }
    }

    private void onBeDamage(Object obj, Object ctx) {
        final DamageEvent dp = (DamageEvent) ctx;
        if (dp.defencer.isSprite() && dp.defencer.hasAI() && dp.defencer.getAI().targetMgr.isValidTarget(dp.attacker.getOwner())) {
            dp.defencer.getAI().trigger(FighterEventsCfg.BE_ATTACK, dp.attacker);
        }
    }

    private void onBeAttack(Object obj, Object ctx) {
        final BeAttackEvent dp = (BeAttackEvent) ctx;
        if (dp.defencer.isSprite() && dp.defencer.hasAI() && dp.defencer.getAI().targetMgr.isValidTarget(dp.attacker.getOwner())) {
            dp.defencer.getAI().trigger(FighterEventsCfg.BE_ATTACK, dp.attacker);
        }
    }

    private void onDeath(Object obj, Object ctx) {
        targetMgr.onDead();
        final DeathEvent dp = (DeathEvent) ctx;
        if (dp.defencer.isSprite() && dp.defencer.hasAI() && dp.defencer.getAI().targetMgr.isValidTarget(dp.attacker.getOwner())) {
            dp.defencer.getAI().trigger(FighterEventsCfg.FIGHTER_DEATH, dp.attacker);
        }
    }

    public FighterAI(int configId) {
        info = CfgMgr.aiconfigcfg.get(configId);
        patrolInfo = CfgMgr.fighterpatrolcfg.get(info.patrolid).patrolinfo;
        originPosInfo = info.originposinfo;
    }


    public void trigger(int event, Object obj) {
        ai.trigger(event, obj);
    }

    protected void setData(Object key, Object value) {
        ai.setData(key, value);
    }

    protected  void removeData(int key) {
        ai.removeData(key);
    }

    protected <T> T getData(int e) {
        return (T) ai.getData(e);
    }

    public Sprite getTarget() {
        Long aid = getData(FighterEnviromentNamesCfg.FIGHTER_TARGET);
        if (aid != null && aid != ConstCfg.NULL) {
            return self.getMap().getSprite(aid);
        }
        return null;
    }

    public Sprite getOwnerTarget() {
        Long aid = getData(FighterEnviromentNamesCfg.OWNER_TARGET);
        if (aid != null && aid != ConstCfg.NULL) {
            return self.getMap().getSprite(aid);
        }
        return null;
    }

    public PatrolInfoCfg getPatrolInfo() {
        return patrolInfo;
    }

    public static boolean isRetinueAI(int id) {
        aiconfigCfg acfg = CfgMgr.aiconfigcfg.get(id);
        return acfg.aiinfo.getTypeId() == RetinueAICfg.TYPEID;
    }

    public aiconfigCfg getAIConfigData() {
        return info;
    }

    public FighterHatredMgr getHatredMgr() {
        return hatredMgr;
    }

    public void initFrameData() {
        frameData = new FighterAIFrameData();
    }

    public FighterTargetMgr getTargetMgr() {
        return targetMgr;
    }

    //endregions
}
