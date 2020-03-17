local PlayerRole    = require"world.character.playerrole"
local modules       = require"modules"
local commons       = require"commons"
local world         = require"world"
-- local Ectype        = Class:new()
local SceneManager  = require"world.map.scenemanager"
local EctypeAction = require"world.ectype.ectypeactions"
local EctypeUIManager = require"world.ectype.ectypeuimanager"
local AreaManager = require"world.ectype.areamanager"
local EctypeMemberMgr = require"world.ectype.team.ectypeteammgr"
local EctypeTaskManager = require"world.ectype.tasks.ectypetaskmanager"

commons.utils.get_or_create("ectype").Ectype = Class:new()
require"world.ectype.ectypeprotocols"
local Ectype = commons.utils.get_or_create("ectype").Ectype

Ectype.EctypeState = enum{
    "NewEctype=-1",
    "Enter=0",
    "LoadingScene",
    "LoadDone",
    "PlayCG",
    "PlayEctype",
}

Ectype.ShowRipple = function()
    commons.camerautils.ShowWaveEffect()
end

function Ectype:SwitchState(state)
    self.m_State = state
    if state == Ectype.EctypeState.NewEctype then
        self.m_CurrAction = self.Start
    elseif state == Ectype.EctypeState.Enter then
        self.m_CurrAction = self.EnterEctype
    elseif state == Ectype.EctypeState.PlayCG then
        self.m_CurrAction = self.PlayCG
    elseif state == Ectype.EctypeState.LoadingScene then
        self.m_CurrAction = self.LoadingScene
    elseif state == Ectype.EctypeState.LoadDone then
        self.m_CurrAction = self.OnLoadDone
    elseif state == Ectype.EctypeState.PlayEctype then
        self.m_CurrAction = self.OnPlayEctype
    else
        self.m_CurrAction = nil
        logError("ectype state error")
    end
end

function Ectype:__new(entryInfo)
    if Local.LogModuals.Ectype then
        printyellow("new ectype",entryInfo.ectypeid)
        printt(entryInfo)
    end
    self.m_EctypeId = entryInfo.ectypeid
    self.m_EntryInfo = entryInfo
    self.m_CfgEctype = commons.configmanager.getConfigData("ectypebase",entryInfo.ectypeid)
    self.m_TypeConfig = commons.configmanager.getConfigData("ectypetypeconfig",self.m_CfgEctype.type)
    self.m_EctypeName = self.m_CfgEctype.ectypename
    self.m_RemainTime = math.floor(self.m_EntryInfo.remaintime / 1000)
    self:SwitchState(Ectype.EctypeState.NewEctype)
    self.m_MembersMgr = EctypeMemberMgr:new(self,entryInfo.teams,entryInfo.teamid)
    self.m_EctypeAI = EctypeAction:new(self.m_CfgEctype,entryInfo.actions,entryInfo.enviroments,self)
    self.m_EctypeUI = EctypeUIManager:new(self)
    if self:IsHuntEctype() then
        self.m_EctypeTask = EctypeTaskManager:new(self.m_CfgEctype, self, entryInfo.activedmissions, entryInfo.finishedmissions)
    else
        self.m_EctypeTask = EctypeTaskManager:new(self.m_CfgEctype, self, entryInfo.activedactions, entryInfo.finishedactions)
    end
    self.m_AreaManager = AreaManager:new(self.m_CfgEctype.regionsetid, self.m_CfgEctype.regionseteffect)
    self.m_bReady = false
    self.m_IsEnd = false
    self.m_ExtraRevive = 0
    self.m_EctypeType = self.m_CfgEctype.type
end

function Ectype:GetTaskMgr()
    return self.m_EctypeTask
end

function Ectype:GetTypeInfo()
    return self.m_TypeConfig
end

function Ectype:OnUIMainShow()
    self.m_EctypeUI:OnUIMainShow()
end

function Ectype:GetEctypeId()
    return self.m_EctypeId
end

function Ectype:GetEctypeType()
    return self.m_CfgEctype.type
end

function Ectype:IsBattleField()
    return self.m_CfgEctype.type > cfg.ectype.EctypeType.BattleGroundEctypes
end

function Ectype:GetRemainTime()
    return self.m_RemainTime
end

function Ectype:GetMembersMgr()
    return self.m_MembersMgr
end

function Ectype:GetType()
    return self.m_CfgEctype.type
end

function Ectype:Start()
    if Local.LogModuals.Ectype then
        printyellow("ectype start",ectypeid)
        printt(ectypeinfo)
    end
    self:SwitchState(Ectype.EctypeState.Enter)

    if self.m_EctypeType > cfg.ectype.EctypeType.BattleGroundEctypes then
        gameevent.enter_battleground:trigger(self.m_EctypeId)
        printyellow("enter battleground")
    end
end

function Ectype:OnLogout()
    self.m_EctypeUI:OnLogout()
end

function Ectype:GetEctypeConfig()
    return self.m_CfgEctype
end

function Ectype:EnterEctype()
    if Local.LogModuals.Ectype then
        printyellow("ectype load map",ectypeid)
        printt(ectypeinfo)
    end
    local cur = SceneManager.GetSceneName()
    self.m_EnterExitFunc = nil
    if cur == self.m_CfgEctype.scenename then
        self.m_EnterExitFunc = Ectype.ShowRipple
    end
    self.m_EctypeUIList = self.m_EctypeUI:GetUIList()
    -- printyellow("startcg??",self.m_EntryInfo.virgin,not IsNullOrEmpty(self.m_CfgEctype.cgnameofbeginning))
    if not IsNullOrEmpty(self.m_CfgEctype.cgnameofbeginning) then
        self:SwitchState(Ectype.EctypeState.PlayCG)
        world.plotmanager.PlotPlay(self.m_CfgEctype.cgnameofbeginning,{
            onEnd = function()
                self:SwitchState(Ectype.EctypeState.LoadingScene)
            end
        })
    else
        self:SwitchState(Ectype.EctypeState.LoadingScene)
    end
    printyellow("SEnter ectype")
    PlayerRole.Instance():sync_SEnterEctype(self.m_EntryInfo)
    SceneManager.LoadScene(self.m_CfgEctype.scenename,{
        dlgList = self.m_EctypeUIList,
        callback = self.m_EnterExitFunc,
        mapType = cfg.map.MapType.ECTYPE,
        ectypeid = self.m_EctypeId
    })
    -- SceneManager.load(self.m_EctypeUIList,self.m_CfgEctype.scenename,self.m_EnterExitFunc)
    -- self:SwitchState(Ectype.EctypeState.LoadingScene)
end

function Ectype:PlayCG()

end

function Ectype:CheckUI()
    for _,v in ipairs(self.m_EctypeUIList) do
        if not modules.uimanager.hasloaded(v) then
            return false
        end
    end
    return modules.uimanager.hasloaded("uimain.dlguimain")
end

function Ectype:LoadingScene(scenename)
    if not SceneManager.IsLoadingScene() and self:CheckUI() then
        self:SwitchState(Ectype.EctypeState.LoadDone)
    end
end

function Ectype:OnLoadDone()
    if Local.LogModuals.Ectype then
        printyellow("ectype load done",ectypeid)
        printt(ectypeinfo)
    end

    gameevent.enter_ectype:trigger(self.m_EctypeId)
    self.m_EctypeAI:init()
    self.m_EctypeTask:Init()
    self.m_EctypeUI:init()
    commons.audiomanager.PlayAudio(self.m_CfgEctype.audioid, defineenum.EAudioChannel.Music)
    self:SendReady()
    modules.uimanager.setlock(true)
    self:SwitchState(Ectype.EctypeState.PlayEctype)
    if self.m_EntryInfo.recordtime > 0 then
        modules.uimanager.show("ectype.dlgectyperecord",{recordtime = self.m_EntryInfo.recordtime,
            recordplayers = self.m_EntryInfo.recordplayers,ectypeid = self.m_EctypeId})
    end
end

function Ectype:GetTeamIndexByTeamId(teamid)
    local myTeamIndex
    local otherTeams = {}
    for i = 1,#self.m_CfgEctype.teamsinfo.teams do
        if bit.band(teamid,bit.lshift(1,i -1)) > 0 then
            myTeamIndex = i
        else
            table.insert(otherTeams,i)
        end
    end
    return myTeamIndex,otherTeams
end

function Ectype:OnDeadCount(total,cnt,remaintime)
    local teamNo = self:GetTeamIndexByTeamId(self.m_EntryInfo.teamid)
    self.m_EntryInfo.deadcount = cnt
    -- local reviveInfo = self.m_CfgEctype.teamsinfo.teams[teamNo].reviveinfo
    self.m_EctypeUI:OnDeadCount{
        remain = total - cnt + 1,
        total = total,
        time = remaintime,
        type = self.m_CfgEctype.revivetype,
        ectype = self,
    }
end

function Ectype:CheckPosition(pos)
    return self.m_AreaManager:InValidAreas(pos) ~= nil
end

function Ectype:IsConnected(from,to)
    return self.m_AreaManager:IsConnected(from,to)
end

function Ectype:RoleEnterEctype()

end

function Ectype:CheckState(state)
    return self.m_State == state
end

function Ectype:IsReady()
    return self.m_bReady
end

function Ectype:OnPlayEctype()
    self.m_EctypeAI:Update()
end

function Ectype:late_update()
    if self:IsReady() then
        self.m_EctypeAI:LateUpdate()
    end
end

function Ectype:ShowWarning()
    if self.m_CfgEctype.type > cfg.ectype.EctypeType.BattleGroundEctypes then
        local b = self.m_MembersMgr:Initiative()
        commons.viewutil.ShowSystemFlyText(LocalString.BattleGround.TimesUpWarning[b])
    end
end

function Ectype:second_update()
    if self:IsReady() then
        self.m_EctypeAI:SecondUpdate()
        if not self.m_EctypeAI:Suspending() then
            if self.m_RemainTime > 0 then
                self.m_RemainTime = self.m_RemainTime - 1
                if self.m_RemainTime <= 60 and self.m_RemainTime > 59 then
                    self:ShowWarning()
                end
            end
        end
    end
end

function Ectype:IsSuspend()
    return self.m_EctypeAI:Suspending()
end

function Ectype:Release()
    self.m_AreaManager:Release()
    self.m_EctypeUI:Leave()
    if self.m_EctypeType > cfg.ectype.EctypeType.BattleGroundEctypes then
        gameevent.leave_battleground:trigger(self.m_EctypeId)
        printyellow("leave_battleground")
    end
end

function Ectype:IsEnd()
    return self.m_IsEnd
end

function Ectype:IsWin()
    return self.m_IsEnd and self.m_IsWin
end

function Ectype:LeaveEctype(msg)
    self.m_EctypeUI:LeaveEctype()
    self.m_EctypeAI:LeaveEctype()
    if self.m_EnterExitFunc then
        self.m_EnterExitFunc()
    end
    modules.uimanager.setlock(false)
    if self.m_IsWin and not IsNullOrEmpty(self.m_CfgEctype.cgnameofend) then
        world.plotmanager.PlotPlay(self.m_CfgEctype.cgnameofend)
    end
end

function Ectype:Update()
    if self.m_CurrAction then
        self.m_CurrAction(self)
    end
    self.m_AreaManager:Update()
end

function Ectype:CanMove()
    return self.m_EctypeAI:CanAbility(cfg.fight.AbilityType.MOVE)
end

function Ectype:CanNormalAttack()
    return self.m_EctypeAI:CanAbility(cfg.fight.AbilityType.NORMAL_ATTACK)
end

function Ectype:CanPlaySkill()
    return self.m_EctypeAI:CanAbility(cfg.fight.AbilityType.SKILL_ATTACK)
end

function Ectype:CanRotate()
    return self.m_EctypeAI:CanAbility(cfg.fight.AbilityType.ROTATE)
end

function Ectype:CanBeAttacked()
    return self.m_EctypeAI:CanAbility(cfg.fight.AbilityType.BEATTACKED)
end

function Ectype:CanAI()
    return self.m_EctypeAI:CanAbility(cfg.fight.AbilityType.AI)
end

function Ectype:GetEnviroment(envname)
    return self.m_EctypeAI:GetEnviroment(envname)
end

function Ectype:GetExtraReviveCount()
    return self.m_ExtraRevive
end

function Ectype:Entered()
    return self.m_State > Ectype.EctypeState.LoadDone
end

function Ectype:DoNavigate()
    self:GetTaskMgr():DoNavigate()
end

function Ectype:IsHuntEctype()
    return self.m_CfgEctype.class == "cfg.ectype.Hunt"
end

return Ectype
