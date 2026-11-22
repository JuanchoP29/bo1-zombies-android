// TEST SWITCH (headless only): bo1_testclient_labs.
//
// Five's special enemies live on the labs floor: the nova crawler (quad) spawners belong to the zones
// labs_hallway1/2 and the thief spawns in the power room, after the power switch there is used
// (maps\_zombiemode_ai_thief::thief_round_tracker waits for flag "power_on"). A player reaches the labs
// by two elevator rides: elevator2 (floor 1 -> war room) and elevator1 (war room -> labs). Driving the
// elevators in general is lane f1's harness work. For the special-enemy runs this switch moves the
// headless fighting test client once, early in the level, into the car of "elevator1" (standing inside
// its "elevator1_up_riders" trigger), so only the first ride is skipped. From then on the harness acts
// like a player and the scripts decide everything: it presses use on the car's buy trigger
// (maps\zombie_pentagon_elevators::elevator_buy_think, 250 points), the car carries it down
// (elevator_move_to sets flag "labs_enabled"; the car's elevator1_down_riders trigger enables zone
// labs_hallway1), then it walks to the map's "use_elec_switch" trigger and presses use
// (maps\zombie_pentagon::electric_switch sets power_on).
//
// Before the move it buys the solo Quick Revive on the spawn floor (as the default harness goal does) and
// waits for the 250 points of the ride; after the power it buys the labs AK74u (and its ammo) at the wall,
// like a player; see SP_LabsWeaponGoal.
// Never writes script state, score, health or ammunition. Registered only in headless runs.
#include "g_sp_headless_labs.h"
#include "g_sp_levelstart.h"
#include <game_mp/g_main_mp.h>
#include <game_mp/g_misc_mp.h>
#include <game_mp/g_utils_mp.h>
#include <win32/win_main.h>
#include <universal/dvar.h>
#include <qcommon/common.h>
#include <server/sv_world.h>
#include <qcommon/cm_trace.h>
#include <clientscript/scr_const.h>
#include <clientscript/cscr_main.h>
#include <clientscript/cscr_vm.h>
#include <clientscript/cscr_stringlist.h>
#include <clientscript/cscr_compiler.h>
#include <game/actor_navigation.h>
#include <game/actor.h>
#include <game_mp/actor_mp.h>
#include <bgame/bg_local.h>
#include <bgame/bg_weapons.h>
#include <bgame/bg_weapons_def.h>
#include <bgame/bg_weapons_ammo.h>
#include <bgame/bg_perks.h>
#include <bgame/bg_misc.h>
#include <bgame/bg_mantle.h>
#include <game/g_weapon.h>
#include <game_mp/g_client_script_cmd_mp.h>
#include <game_mp/g_spawn_mp.h>
#include <qcommon/cmd.h>
#include "g_sp_player_state.h"
#include <cstring>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#ifndef _WIN32
#include <sys/stat.h>
#else
#include <direct.h>
#endif

static const dvar_s *g_headlessLabs;
static bool g_labsMoved;

void G_SP_HeadlessLabsInit()
{
    g_labsMoved = false;
    if (Sys_IsHeadless())
        g_headlessLabs = _Dvar_RegisterBool("bo1_testclient_labs", false, 0,
            "TEST SWITCH (headless test client only): move the fighting test client next to Five's labs elevator "
            "(into the elevator1 car, riders trigger) early in the level; it then rides elevator1 down and uses use_elec_switch");
}

bool G_SP_HeadlessLabsActive()
{
    return Sys_IsHeadless() && G_SP_IsZombieMode() && g_headlessLabs && g_headlessLabs->current.enabled;
}

static bool SP_LabsHasTargetname(const gentity_s *ent, const char *name)
{
    return ent->r.inuse && ent->targetname
        && !strcmp(SL_ConvertToString(ent->targetname, SCRIPTINSTANCE_SERVER), name);
}

static gentity_s *SP_LabsFindTargetname(const char *name)
{
    for (int i = 0; i < level.num_entities; ++i)
    {
        if (SP_LabsHasTargetname(&g_entities[i], name))
            return &g_entities[i];
    }
    return nullptr;
}

static void SP_LabsCenter(const gentity_s *ent, float *center)
{
    for (int i = 0; i < 3; ++i)
        center[i] = 0.5f * (ent->r.absmin[i] + ent->r.absmax[i]);
}

// Floor point under a trigger's center where a standing player hull fits.
static bool SP_LabsStandPoint(gentity_s *player, const gentity_s *trigger, float *point)
{
    float center[3], down[3];
    SP_LabsCenter(trigger, center);
    Vec3Copy(center, down);
    down[2] -= 256.0f;
    trace_t trace;
    col_context_t context;
    G_TraceCapsule(&trace, center, vec3_origin, vec3_origin, down, player->s.number, player->clipmask, &context);
    if (trace.startsolid || trace.fraction >= 1.0f)
        return false;
    Vec3Lerp(center, down, trace.fraction, point);
    point[2] += 1.0f;
    float up[3] = { point[0], point[1], point[2] + 1.0f };
    G_TraceCapsule(&trace, point, playerMins, playerMaxs, up, player->s.number, player->clipmask, &context);
    return !trace.startsolid && !trace.allsolid;
}

static gentity_s *SP_LabsFindPiece(const char *owner, int classname)
{
    const gentity_s *car = SP_LabsFindTargetname(owner);
    if (!car || !car->target)
        return nullptr;
    const char *pieces = SL_ConvertToString(car->target, SCRIPTINSTANCE_SERVER);
    for (int i = 0; i < level.num_entities; ++i)
    {
        gentity_s *ent = &g_entities[i];
        if (ent->classname == classname && SP_LabsHasTargetname(ent, pieces))
            return ent;
    }
    return nullptr;
}

// Once: stand the player in elevator1's car, inside its elevator1_up_riders trigger.
static void SP_LabsMove(gentity_s *player)
{
    g_labsMoved = true;
    const gentity_s *riders = SP_LabsFindTargetname("elevator1_up_riders");
    float point[3];
    if (!riders || !SP_LabsStandPoint(player, riders, point))
    {
        Com_Printf(16, "bo1_labs: elevator1_up_riders missing or no standing room; test client not moved\n");
        return;
    }
    float angles[3] = { 0.0f, player->client->ps.viewangles[1], 0.0f };
    TeleportPlayer(player, point, angles);
    Com_Printf(16, "bo1_labs: test client moved into elevator1 at %.1f %.1f %.1f (trigger ent %d) at %d\n",
        point[0], point[1], point[2], riders->s.number, level.time);
}

// The trigger_use that targets `target` (a purchase: wall weapon, perk machine).
static gentity_s *SP_LabsFindTrigger(const char *target)
{
    for (int i = 0; i < level.num_entities; ++i)
    {
        gentity_s *ent = &g_entities[i];
        if (ent->r.inuse && ent->classname == scr_const.trigger_use && ent->target
            && !strcmp(SL_ConvertToString(ent->target, SCRIPTINSTANCE_SERVER), target))
            return ent;
    }
    return nullptr;
}

// After the power: the labs' AK74u wall buy (weapon_upgrade trigger "ak74u_zm", which targets
// pf884_auto37), so the test client can hold the labs through rounds 5-8 where the thief round is
// drawn. Harness policy only: walk there and press use when the score could pay for the gun (1200) or,
// once it is held and empty, its ammo (600); maps\_zombiemode_weapons::weapon_spawn_think decides.
static gentity_s *SP_LabsWeaponGoal(gentity_s *player)
{
    const unsigned int weapon = BG_FindWeaponIndexForName("ak74u_zm"); // lookup only, never registers
    const playerState_s *ps = &player->client->ps;
    const int score = player->client->sess.cs.score.score;
    if (!weapon)
        return nullptr;
    if (BG_PlayerHasWeapon(ps, weapon))
    {
        if (score < 600 || BG_GetAmmoInClip(ps, weapon) + BG_GetAmmoNotInClip(ps, weapon) > 0)
            return nullptr;
    }
    else if (score < 1200)
    {
        return nullptr;
    }
    return SP_LabsFindTrigger("pf884_auto37");
}

gentity_s *G_SP_HeadlessLabsGoal(gentity_s *player)
{
    // electric_switch deletes use_elec_switch once used, so no trigger = power is on.
    gentity_s *power = SP_LabsFindTargetname("use_elec_switch");
    if (!power)
        return SP_LabsWeaponGoal(player);
    if (!g_labsMoved)
    {
        // First, on the spawn floor, the solo Quick Revive (500) like the default harness goal, then the
        // move once the elevator1 ride (250) is affordable.
        if (!G_SP_HasPerk(player->client, "specialty_quickrevive"))
            return player->client->sess.cs.score.score >= 500 ? SP_LabsFindTrigger("vending_revive") : nullptr;
        if (player->client->sess.cs.score.score >= 250)
            SP_LabsMove(player);
        return nullptr;
    }
    float powerCenter[3];
    SP_LabsCenter(power, powerCenter);
    // On the switch's floor: walk to it and press use.
    if (fabsf(player->r.currentOrigin[2] - powerCenter[2]) < 128.0f)
        return power;
    // Still upstairs: press use on the car's buy trigger while it is in reach (elevator_buy_think moves
    // it away with trigger_off while the car travels).
    gentity_s *buy = SP_LabsFindPiece("elevator1", scr_const.trigger_use);
    if (!buy)
        return nullptr;
    float buyCenter[3];
    SP_LabsCenter(buy, buyCenter);
    return Vec3DistanceSq(buyCenter, player->r.currentOrigin) < 200.0f * 200.0f ? buy : nullptr;
}

// The Pentagon Thief (aitype actor_zombie_electrician, maps\_zombiemode_ai_thief) while alive, for the
// labs switch's fight policy: once it has stolen a weapon it runs through its portals and leaves after
// about 40 s, and thief_return_loot (the GiveWeapon with get_pack_a_punch_weapon_options) only runs when a
// player kills it first. Harness policy only: with a loaded weapon the test client shoots the thief before
// other zombies and walks toward it when it is out of sight; the scripts and the damage code decide the rest.
gentity_s *G_SP_HeadlessLabsThief(gentity_s *player)
{
    if (!G_SP_HeadlessLabsActive() || !level.actors)
        return nullptr;
    const playerState_s *ps = &player->client->ps;
    const unsigned int weapon = ps->weapon;
    if (!weapon || BG_GetAmmoInClip(ps, weapon) + BG_GetAmmoNotInClip(ps, weapon) <= 0)
        return nullptr;
    for (int i = 0; i < MAX_ACTORS; ++i)
    {
        actor_s *actor = &level.actors[i];
        gentity_s *ent = actor->ent;
        if (actor->inuse && ent && ent->health > 0 && actor->Physics.bIsAlive && ent->classname
            && !strcmp(SL_ConvertToString(ent->classname, SCRIPTINSTANCE_SERVER), "actor_zombie_electrician"))
            return ent;
    }
    return nullptr;
}

// TEST SWITCH (headless test client only): bo1_testclient_round N.
//
// Rounds 13-15 take about 15 min of fighting from mutator_quickStart's round 5, longer than the test
// client survives. The retail way to start later is maps\_zombiemode::difficulty_init: mutator_quickStart
// sets level.first_round = false and level.round_number = 5. This switch writes the SAME variable,
// level.round_number = N, once, when round_start sets flag "begin_spawning" (flag_set notifies level).
// That is after difficulty_init and before round_start threads round_think, so round_think and
// round_spawning (ai_calculate_health( level.round_number ), zombie_total) start from round N exactly as
// they start from quickStart's 5. Use it together with mutator_quickStart 1 (first_round false).
// Only in headless runs with bo1_testclient 1; writes nothing else.
static const dvar_s *g_headlessRound;
static unsigned int g_roundNumberKey;
// L26 TEST SWITCH (headless test client only): bo1_testclient_dogs 1 makes every round after round 1 a dog round.
// It is the body of maps\_zombiemode_ai_dogs' /# force_dogs #/ devgui dvar (developer_script only in retail):
// level.next_dog_round = level.round_number, written on the level notify "between_round_over" (sent after
// round_number++), before dog_round_tracker's waittill wakes and compares the two.
static const dvar_s *g_headlessDogs;
static unsigned int g_nextDogRoundKey;

static const dvar_s *g_zmAutoloadDvar;
static unsigned int g_firstRoundKey;
static unsigned int g_zombieMoveSpeedKey;
static int g_zmGivePerkHandle;
static cmd_function_s s_zmSaveCmd, s_zmLoadCmd;
static bool s_zmCmdsRegistered;

static const int kZmMaxTriggers = 128;
static const int kZmMaxDoors = 96;

struct ZmSavedWeapon
{
    char name[64];
    int clip;
    int stock;
};

struct ZmMapTrigger
{
    int entNum;
    float origZ;
    float origCenterZ;
    bool isPower;
};

struct ZmCheckpoint
{
    int phase;            // 0 = idle, 1 = power & doors, 2 = retry power-gated doors, 3 = player gear/score/pos
    int spawnTime;
    char map[64];
    int round;
    int score;
    int kills;
    int deaths;
    int assists;
    bool hasCols;
    int sbCols[5];
    int health;
    int maxHealth;
    bool hasPos;
    float pos[3];
    float yaw;
    bool power;
    int doorCount;
    int doors[kZmMaxDoors];
    char activeWeapon[64];
    int weaponCount;
    ZmSavedWeapon weapons[12];
    int perkCount;
    char perks[10][48];
};

static ZmCheckpoint s_zmCp;
static ZmMapTrigger s_mapTriggers[kZmMaxTriggers];
static int s_mapTriggerCount;
static bool s_mapTriggersCaptured;
static bool s_powerOn;

static bool ZmIsDoorOrDebris(const gentity_s *ent)
{
    if (!ent || !ent->r.inuse || !ent->targetname)
        return false;
    const char *tn = SL_ConvertToString(ent->targetname, SCRIPTINSTANCE_SERVER);
    return tn && (!strcmp(tn, "zombie_door") || !strcmp(tn, "zombie_debris") || !strcmp(tn, "zombie_airlock_buy"));
}

static bool ZmIsPowerSwitch(const gentity_s *ent)
{
    if (!ent || !ent->r.inuse || !ent->targetname)
        return false;
    const char *tn = SL_ConvertToString(ent->targetname, SCRIPTINSTANCE_SERVER);
    return tn && (!strcmp(tn, "use_elec_switch") || !strcmp(tn, "use_power_switch")
        || !strcmp(tn, "use_master_switch") || !strcmp(tn, "power_trigger_left")
        || !strcmp(tn, "power_trigger_right"));
}

static float ZmTriggerCenterZ(const gentity_s *ent)
{
    return 0.5f * (ent->r.absmin[2] + ent->r.absmax[2]);
}

static const ZmMapTrigger *ZmFindTriggerSnapshot(int entNum)
{
    for (int i = 0; i < s_mapTriggerCount; ++i)
    {
        if (s_mapTriggers[i].entNum == entNum)
            return &s_mapTriggers[i];
    }
    return nullptr;
}

static bool ZmIsDoorOpened(int entNum)
{
    if (entNum < 0 || entNum >= level.num_entities)
        return true;
    const gentity_s *ent = &g_entities[entNum];
    if (!ent->r.inuse || !ent->r.linked)
        return true;
    const ZmMapTrigger *mt = ZmFindTriggerSnapshot(entNum);
    if (mt)
        return (ent->r.currentOrigin[2] < mt->origZ - 200.0f)
            || (ZmTriggerCenterZ(ent) < mt->origCenterZ - 200.0f);
    return ZmTriggerCenterZ(ent) < -5000.0f;
}

static void ZmCaptureInitialTriggers()
{
    s_mapTriggerCount = 0;
    for (int i = 0; i < level.num_entities && s_mapTriggerCount < kZmMaxTriggers; ++i)
    {
        const gentity_s *ent = &g_entities[i];
        const bool isDoor = ZmIsDoorOrDebris(ent);
        const bool isPow = !isDoor && ZmIsPowerSwitch(ent);
        if (!isDoor && !isPow)
            continue;
        ZmMapTrigger &mt = s_mapTriggers[s_mapTriggerCount++];
        mt.entNum = i;
        mt.origZ = ent->r.currentOrigin[2];
        mt.origCenterZ = ZmTriggerCenterZ(ent);
        mt.isPower = isPow;
    }
    s_mapTriggersCaptured = true;
}

static const char *kZmPerkList[] = {
    "specialty_armorvest",
    "specialty_fastreload",
    "specialty_quickrevive",
    "specialty_rof",
    "specialty_longersprint",
    "specialty_flakjacket",
    "specialty_deadshot",
    "specialty_additionalprimaryweapon",
};

static void ZmSanitizeSlot(const char *in, char *out, size_t outSize)
{
    size_t j = 0;
    if (in)
    {
        for (size_t i = 0; in[i] && j + 1 < outSize; ++i)
        {
            const char c = in[i];
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')
                out[j++] = c;
        }
    }
    if (j == 0 && outSize > 6)
    {
        strcpy(out, "slot1");
        return;
    }
    out[j] = '\0';
}

static bool ZmBuildSavePath(const char *slot, char *outPath, size_t outSize, bool createDir)
{
    const char *home = Dvar_GetString("fs_h");
    if (!home || !home[0])
        home = Dvar_GetString("fs_homepath");
    if (!home || !home[0])
    {
        Com_Printf(16, "zm_save/load: fs_h is empty\n");
        return false;
    }
    char dir[512];
    snprintf(dir, sizeof(dir), "%s/zm_saves", home);
    if (createDir)
    {
#ifndef _WIN32
        mkdir(dir, 0775);
#else
        _mkdir(dir);
#endif
    }
    char safe[48];
    ZmSanitizeSlot(slot, safe, sizeof(safe));
    snprintf(outPath, outSize, "%s/%s.txt", dir, safe);
    return true;
}

static bool ZmParseCheckpointFile(const char *path, ZmCheckpoint *cp)
{
    memset(cp, 0, sizeof(*cp));
    cp->round = 1;
    cp->score = 500;
    cp->health = 100;
    cp->maxHealth = 100;
    FILE *f = fopen(path, "r");
    if (!f)
        return false;
    char line[256];
    while (fgets(line, sizeof(line), f))
    {
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r'))
            line[--len] = '\0';
        if (!strncmp(line, "map=", 4))
            snprintf(cp->map, sizeof(cp->map), "%s", line + 4);
        else if (!strncmp(line, "round=", 6))
            cp->round = atoi(line + 6);
        else if (!strncmp(line, "score=", 6))
            cp->score = atoi(line + 6);
        else if (!strncmp(line, "kills=", 6))
            cp->kills = atoi(line + 6);
        else if (!strncmp(line, "deaths=", 7))
            cp->deaths = atoi(line + 7);
        else if (!strncmp(line, "assists=", 8))
            cp->assists = atoi(line + 8);
        else if (!strncmp(line, "sbcols=", 7))
        {
            if (sscanf(line + 7, "%d %d %d %d %d",
                    &cp->sbCols[0], &cp->sbCols[1], &cp->sbCols[2], &cp->sbCols[3], &cp->sbCols[4]) == 5)
                cp->hasCols = true;
        }
        else if (!strncmp(line, "health=", 7))
            cp->health = atoi(line + 7);
        else if (!strncmp(line, "maxhealth=", 10))
            cp->maxHealth = atoi(line + 10);
        else if (!strncmp(line, "pos=", 4))
        {
            if (sscanf(line + 4, "%f %f %f %f", &cp->pos[0], &cp->pos[1], &cp->pos[2], &cp->yaw) >= 3)
                cp->hasPos = true;
        }
        else if (!strncmp(line, "power=", 6))
            cp->power = atoi(line + 6) != 0;
        else if (!strncmp(line, "door=", 5) && cp->doorCount < kZmMaxDoors)
            cp->doors[cp->doorCount++] = atoi(line + 5);
        else if (!strncmp(line, "active=", 7))
            snprintf(cp->activeWeapon, sizeof(cp->activeWeapon), "%s", line + 7);
        else if (!strncmp(line, "weapon=", 7) && cp->weaponCount < 12)
        {
            ZmSavedWeapon &w = cp->weapons[cp->weaponCount];
            if (sscanf(line + 7, "%63s %d %d", w.name, &w.clip, &w.stock) == 3)
                ++cp->weaponCount;
        }
        else if (!strncmp(line, "perk=", 5) && cp->perkCount < 10)
        {
            snprintf(cp->perks[cp->perkCount], sizeof(cp->perks[0]), "%s", line + 5);
            ++cp->perkCount;
        }
    }
    fclose(f);
    if (cp->round < 1) cp->round = 1;
    if (cp->round > 255) cp->round = 255;
    if (cp->score < 0) cp->score = 0;
    if (cp->maxHealth < 100) cp->maxHealth = 100;
    return cp->map[0] != '\0';
}

static void G_SP_ZmSave_f()
{
    if (!G_SP_IsZombieMode() || !g_entities[0].r.inuse || !g_entities[0].client)
    {
        Com_Printf(16, "zm_save: not in an active Zombies match\n");
        return;
    }
    const char *slot = Cmd_Argc() >= 2 ? Cmd_Argv(1) : "slot1";
    char path[560];
    if (!ZmBuildSavePath(slot, path, sizeof(path), true))
        return;

    gentity_s *player = &g_entities[0];
    gclient_s *client = player->client;
    playerState_s *ps = &client->ps;
    const char *mapName = Dvar_GetString("mapname");

    int round = 1;
    if (g_roundNumberKey && gScrVarPub[SCRIPTINSTANCE_SERVER].levelId)
    {
        unsigned int id = FindVariable(SCRIPTINSTANCE_SERVER, gScrVarPub[SCRIPTINSTANCE_SERVER].levelId, g_roundNumberKey);
        if (id && GetValueType(SCRIPTINSTANCE_SERVER, id) == VAR_INTEGER)
        {
            round = GetVariableValueAddress(SCRIPTINSTANCE_SERVER, id)->u.intValue;
            if (round < 1) round = 1;
        }
    }

    FILE *f = fopen(path, "w");
    if (!f)
    {
        Com_Printf(16, "zm_save: failed to open %s\n", path);
        return;
    }

    const int score = client->sess.cs.score.score;
    const int maxHp = client->sess.maxHealth > 100 ? client->sess.maxHealth : (player->maxHealth > 100 ? player->maxHealth : 100);
    const int hp = player->health > 0 ? player->health : maxHp;
    const char *activeName = "";
    if (ps->weapon)
    {
        const WeaponVariantDef *vdef = BG_GetWeaponVariantDef(ps->weapon);
        if (vdef && vdef->szInternalName)
            activeName = vdef->szInternalName;
    }

    fprintf(f, "map=%s\n", mapName ? mapName : "");
    fprintf(f, "round=%d\n", round);
    fprintf(f, "score=%d\n", score);
    fprintf(f, "kills=%d\n", client->sess.cs.score.kills);
    fprintf(f, "deaths=%d\n", client->sess.cs.score.deaths);
    fprintf(f, "assists=%d\n", client->sess.cs.score.assists);
    fprintf(f, "sbcols=%d %d %d %d %d\n",
        client->sess.cs.score.scoreboardColumns[0],
        client->sess.cs.score.scoreboardColumns[1],
        client->sess.cs.score.scoreboardColumns[2],
        client->sess.cs.score.scoreboardColumns[3],
        client->sess.cs.score.scoreboardColumns[4]);
    fprintf(f, "health=%d\n", hp);
    fprintf(f, "maxhealth=%d\n", maxHp);
    fprintf(f, "pos=%.1f %.1f %.1f %.1f\n",
        player->r.currentOrigin[0], player->r.currentOrigin[1], player->r.currentOrigin[2],
        ps->viewangles[1]);
    fprintf(f, "power=%d\n", s_powerOn ? 1 : 0);
    fprintf(f, "time=%lld\n", (long long)time(nullptr));
    fprintf(f, "active=%s\n", activeName);

    int savedDoors = 0;
    for (int i = 0; i < s_mapTriggerCount; ++i)
    {
        const ZmMapTrigger &mt = s_mapTriggers[i];
        if (mt.isPower || mt.entNum < 0 || mt.entNum >= level.num_entities)
            continue;
        if (ZmIsDoorOpened(mt.entNum))
        {
            fprintf(f, "door=%d\n", mt.entNum);
            ++savedDoors;
        }
    }

    int savedWeapons = 0;
    for (int i = 0; i < 15; ++i)
    {
        const unsigned int w = ps->heldWeapons[i].weapon;
        if (!w)
            continue;
        const WeaponVariantDef *vdef = BG_GetWeaponVariantDef(w);
        if (!vdef || !vdef->szInternalName || !vdef->szInternalName[0])
            continue;
        const char *wn = vdef->szInternalName;
        if (strstr(wn, "bottle") || strstr(wn, "syrette") || strstr(wn, "knuckle_crack"))
            continue;
        const int clip = BG_GetAmmoInClip(ps, w);
        const int stock = BG_GetAmmoNotInClip(ps, w);
        fprintf(f, "weapon=%s %d %d\n", wn, clip, stock);
        ++savedWeapons;
    }

    int savedPerks = 0;
    for (size_t i = 0; i < sizeof(kZmPerkList) / sizeof(kZmPerkList[0]); ++i)
    {
        if (G_SP_HasPerk(client, kZmPerkList[i]))
        {
            fprintf(f, "perk=%s\n", kZmPerkList[i]);
            ++savedPerks;
        }
    }
    fclose(f);
    Com_Printf(16, "zm_save: saved '%s' (%s R%d pts=%d weapons=%d perks=%d doors=%d power=%d)\n",
        slot, mapName ? mapName : "", round, score, savedWeapons, savedPerks, savedDoors, s_powerOn ? 1 : 0);
}

static void G_SP_ZmLoad_f()
{
    if (Cmd_Argc() < 2)
        return;
    char safe[48];
    ZmSanitizeSlot(Cmd_Argv(1), safe, sizeof(safe));
    char path[560];
    if (!ZmBuildSavePath(safe, path, sizeof(path), false))
        return;
    ZmCheckpoint tmp;
    if (!ZmParseCheckpointFile(path, &tmp))
    {
        Com_Printf(16, "zm_load: checkpoint '%s' not found or invalid\n", safe);
        return;
    }
    Com_Printf(16, "zm_load: launching %s (R%d, score=%d)\n", tmp.map, tmp.round, tmp.score);
    Cbuf_AddText(0, va("set systemlink 0\nset bo1_expected_players 0\nset bo1_zm_autoload \"%s\"\nmap %s\n",
        safe, tmp.map));
}

static void ZmRunDoorAndPowerPass(gentity_s *player, bool includePower)
{
    gclient_s *client = player->client;
    playerState_s *ps = &client->ps;

    bool needPower = includePower && s_zmCp.power && !s_powerOn;
    bool needDoor = false;
    for (int i = 0; i < s_zmCp.doorCount; ++i)
    {
        if (!ZmIsDoorOpened(s_zmCp.doors[i]))
        {
            needDoor = true;
            break;
        }
    }
    if (!needPower && !needDoor)
        return;

    // Bind level_bgs, clear blackscreen control-freeze flag (0x400000), and hold Use (bit 3) while running
    // Scr_RunCurrentThreads immediately after each Scr_Notify so door_buy() / debris_think() execute with
    // `who UseButtonPressed() == 1` instead of waking on the next frame after ClientThink clears buttons.
    bgs_t *savedBgs = bgs;
    bgs = &level_bgs;
    const int savedPmFlags = ps->pm_flags;
    ps->pm_flags &= ~0x400000;
    const auto savedButtons = client->button_bits;
    const auto savedButtonsSince = client->button_bitsSinceLastFrame;
    client->button_bits.setBit(3u);
    client->button_bitsSinceLastFrame.setBit(3u);
    client->sess.cs.score.score = 1000000;

    if (needPower)
    {
        for (int i = 0; i < s_mapTriggerCount; ++i)
        {
            if (!s_mapTriggers[i].isPower)
                continue;
            const int entNum = s_mapTriggers[i].entNum;
            if (entNum >= 0 && entNum < level.num_entities && ZmIsPowerSwitch(&g_entities[entNum]) && !ZmIsDoorOpened(entNum))
            {
                Scr_AddEntity(player, SCRIPTINSTANCE_SERVER);
                Scr_Notify(&g_entities[entNum], scr_const.trigger, 1u);
                Scr_RunCurrentThreads(SCRIPTINSTANCE_SERVER);
            }
        }
    }

    for (int i = 0; i < s_zmCp.doorCount; ++i)
    {
        const int entNum = s_zmCp.doors[i];
        if (ZmIsDoorOpened(entNum))
            continue; // Also skips sibling trigger on the other side once the first side opens & disables both
        gentity_s *trig = &g_entities[entNum];
        if (ZmIsDoorOrDebris(trig))
        {
            Scr_AddEntity(player, SCRIPTINSTANCE_SERVER);
            Scr_Notify(trig, scr_const.trigger, 1u);
            Scr_RunCurrentThreads(SCRIPTINSTANCE_SERVER);
        }
    }

    client->button_bits = savedButtons;
    client->button_bitsSinceLastFrame = savedButtonsSince;
    ps->pm_flags = savedPmFlags;
    bgs = savedBgs;
}

static bool ZmHasPendingDoors()
{
    for (int i = 0; i < s_zmCp.doorCount; ++i)
    {
        if (!ZmIsDoorOpened(s_zmCp.doors[i]))
            return true;
    }
    return false;
}

void G_SP_ZmCheckpointFrame()
{
    if (!G_SP_IsZombieMode())
        return;
    if (!s_mapTriggersCaptured && level.time >= 600)
        ZmCaptureInitialTriggers();
    if (s_zmCp.phase == 0)
        return;
    if (!g_entities[0].r.inuse || !g_entities[0].client || g_entities[0].health <= 0)
        return;

    gentity_s *player = &g_entities[0];
    gclient_s *client = player->client;
    playerState_s *ps = &client->ps;

    // Phase 1: Turn on power & re-open saved doors/debris natively via BO1's own trigger scripts.
    if (s_zmCp.phase == 1)
    {
        if (level.time < s_zmCp.spawnTime + 1000)
            return;
        if (!s_mapTriggersCaptured)
            ZmCaptureInitialTriggers();
        ZmRunDoorAndPowerPass(player, true);
        s_zmCp.phase = (ZmHasPendingDoors() || (s_zmCp.power && !s_powerOn)) ? 2 : 3;
        return;
    }

    // Phase 2: Retry any doors that were waiting on power_on or initial blackscreen fade.
    if (s_zmCp.phase == 2)
    {
        if (s_zmCp.power && !s_powerOn && level.time < s_zmCp.spawnTime + 2200)
            return;
        if (level.time < s_zmCp.spawnTime + 1450)
            return;
        ZmRunDoorAndPowerPass(player, false);
        s_zmCp.phase = 3;
        return;
    }

    // Phase 3: Restore perks (crash-free G_SP_SetPerk + give_perk), health, weapons + ammo, exact score, and position.
    if (level.time < s_zmCp.spawnTime + 1500)
        return;
    s_zmCp.phase = 0;

    // 1. Restore Perks + Health via official maps/_zombiemode_perks::give_perk (HUD icons + Quick Revive solo lives + Juggernog)
    bool hasJugg = false;
    for (int i = 0; i < s_zmCp.perkCount; ++i)
    {
        if (!s_zmCp.perks[i][0])
            continue;
        if (g_zmGivePerkHandle)
        {
            Scr_AddString(s_zmCp.perks[i], SCRIPTINSTANCE_SERVER);
            Scr_FreeThread(Scr_ExecEntThread(player, g_zmGivePerkHandle, 1u), SCRIPTINSTANCE_SERVER);
        }
        G_SP_SetPerk(client, s_zmCp.perks[i], true);
        if (!strcmp(s_zmCp.perks[i], "specialty_armorvest"))
            hasJugg = true;
    }
    if (hasJugg || s_zmCp.maxHealth > 100)
    {
        const int targetMax = s_zmCp.maxHealth > 100 ? s_zmCp.maxHealth : 250;
        client->sess.maxHealth = targetMax;
        ps->stats[2] = targetMax;
        ps->stats[0] = targetMax;
        player->maxHealth = targetMax;
        player->health = targetMax;
    }

    // 2. Restore Weapons + Exact Clip & Reserve Ammo
    if (s_zmCp.weaponCount > 0)
    {
        for (int i = 0; i < 15; ++i)
        {
            const unsigned int held = ps->heldWeapons[i].weapon;
            if (!held)
                continue;
            const WeaponDef *def = BG_GetWeaponDef(held);
            if (def && def->inventoryType == WEAPINVENTORY_PRIMARY)
                BG_TakePlayerWeapon(ps, held);
        }
        unsigned int selectIdx = 0;
        for (int i = 0; i < s_zmCp.weaponCount; ++i)
        {
            const ZmSavedWeapon &sw = s_zmCp.weapons[i];
            const unsigned int w = BG_GetWeaponIndexForName(sw.name);
            if (!w)
                continue;
            if (G_GivePlayerWeapon(ps, w, 0, 0))
            {
                G_InitializeAmmo(player, w, 0, 0);
                const int clipIdx = BG_ClipForWeapon(w);
                if (clipIdx > 0)
                {
                    int c = sw.clip;
                    const int maxClip = BG_GetClipSize(w);
                    if (c < 0) c = 0;
                    if (c > maxClip) c = maxClip;
                    BG_SetAmmoInClip(ps, clipIdx, c);
                }
                if (!BG_WeaponIsClipOnly(w) && BG_AmmoForWeapon(w))
                {
                    int st = sw.stock;
                    const int maxStock = BG_GetAmmoPlayerMax(ps, w, 0);
                    if (st < 0) st = 0;
                    if (st > maxStock) st = maxStock;
                    BG_SetAmmoInPool(ps, w, st);
                }
                if (!strcmp(sw.name, s_zmCp.activeWeapon))
                    selectIdx = w;
                else if (!selectIdx)
                {
                    const WeaponDef *def = BG_GetWeaponDef(w);
                    if (def && def->inventoryType == WEAPINVENTORY_PRIMARY)
                        selectIdx = w;
                }
            }
        }
        if (selectIdx)
            G_SelectWeaponIndex(0, (int)selectIdx);
    }

    // 3. Restore Exact Score & Scoreboard Stats (Kills, Downs, Revives, Headshots)
    client->sess.cs.score.score = s_zmCp.score;
    if (s_zmCp.kills > 0)
        client->sess.cs.score.kills = s_zmCp.kills;
    if (s_zmCp.deaths > 0)
        client->sess.cs.score.deaths = s_zmCp.deaths;
    if (s_zmCp.assists > 0)
        client->sess.cs.score.assists = s_zmCp.assists;
    if (s_zmCp.hasCols)
    {
        for (int i = 0; i < 5; ++i)
            client->sess.cs.score.scoreboardColumns[i] = s_zmCp.sbCols[i];
    }
    CalculateRanks();

    // 4. Restore Saved Position (if present)
    if (s_zmCp.hasPos)
    {
        float angles[3] = { 0.0f, s_zmCp.yaw, 0.0f };
        TeleportPlayer(player, s_zmCp.pos, angles);
    }

    int openedDoors = 0;
    for (int i = 0; i < s_zmCp.doorCount; ++i)
    {
        if (ZmIsDoorOpened(s_zmCp.doors[i]))
            ++openedDoors;
    }
    Com_Printf(16, "zm_load: restored %s R%d pts=%d kills=%d weapons=%d perks=%d (give_perk=%d) doors=%d/%d power=%d\n",
        s_zmCp.map, s_zmCp.round, s_zmCp.score, s_zmCp.kills, s_zmCp.weaponCount, s_zmCp.perkCount,
        g_zmGivePerkHandle, openedDoors, s_zmCp.doorCount, s_zmCp.power ? 1 : 0);
}

void G_SP_HeadlessRoundFields(scriptInstance_t inst)
{
    if (inst != SCRIPTINSTANCE_SERVER)
        return;
    // Always capture canonical round_number / first_round / zombie_move_speed keys and reset per-map trigger snapshot.
    g_roundNumberKey = 0;
    g_firstRoundKey = 0;
    g_zombieMoveSpeedKey = 0;
    g_nextDogRoundKey = 0;
    g_zmGivePerkHandle = Scr_GetFunctionHandle(inst, "maps/_zombiemode_perks", "give_perk");
    s_mapTriggersCaptured = false;
    s_mapTriggerCount = 0;
    s_powerOn = false;
    s_zmCp.phase = 0;
    if (!g_zmAutoloadDvar)
        g_zmAutoloadDvar = _Dvar_RegisterString("bo1_zm_autoload", "", 0, "mod (zombies): checkpoint slot to restore on map load");
    if (!s_zmCmdsRegistered)
    {
        s_zmCmdsRegistered = true;
        Cmd_AddCommandInternal("zm_save", G_SP_ZmSave_f, &s_zmSaveCmd);
        Cmd_AddCommandInternal("zm_load", G_SP_ZmLoad_f, &s_zmLoadCmd);
    }
    unsigned int string = SL_FindString("round_number", inst);
    g_roundNumberKey = string ? gScrCompilePub[inst].canonicalStrings[string] : 0;
    string = SL_FindString("first_round", inst);
    g_firstRoundKey = string ? gScrCompilePub[inst].canonicalStrings[string] : 0;
    string = SL_FindString("zombie_move_speed", inst);
    g_zombieMoveSpeedKey = string ? gScrCompilePub[inst].canonicalStrings[string] : 0;

    if (!Sys_IsHeadless())
        return;
    if (!g_headlessDogs)
        g_headlessDogs = _Dvar_RegisterBool("bo1_testclient_dogs", false, 0,
            "TEST SWITCH (headless test client only): force_dogs - level.next_dog_round = level.round_number at "
            "between_round_over");
    if (!g_headlessRound)
        g_headlessRound = _Dvar_RegisterInt("bo1_testclient_round", 0, 0, 100, 0,
            "TEST SWITCH (headless test client only): set level.round_number to N when round_start sets "
            "flag begin_spawning (use with mutator_quickStart 1); 0 = off");
    string = SL_FindString("next_dog_round", inst);
    g_nextDogRoundKey = string ? gScrCompilePub[inst].canonicalStrings[string] : 0;
}

static void G_SP_HeadlessDogsNotify(scriptInstance_t inst, unsigned int owner, unsigned int name)
{
    if (!g_headlessDogs || !g_headlessDogs->current.enabled || !g_nextDogRoundKey || !g_roundNumberKey
        || !name || strcmp(SL_ConvertToString(name, inst), "between_round_over"))
        return;
    unsigned int roundId = FindVariable(inst, owner, g_roundNumberKey);
    unsigned int dogId = FindVariable(inst, owner, g_nextDogRoundKey);
    if (!roundId || !dogId || GetValueType(inst, roundId) != VAR_INTEGER || GetValueType(inst, dogId) != VAR_INTEGER)
        return;
    const int round = GetVariableValueAddress(inst, roundId)->u.intValue;
    VariableUnion &value = GetVariableValueAddress(inst, dogId)->u;
    Com_Printf(16, "TEST SWITCH bo1_testclient_dogs: level.next_dog_round %d -> %d\n", value.intValue, round);
    value.intValue = round;
}

void G_SP_HeadlessRoundNotify(scriptInstance_t inst, unsigned int owner, unsigned int name)
{
    if (inst != SCRIPTINSTANCE_SERVER || owner != gScrVarPub[inst].levelId || !G_SP_IsZombieMode())
        return;
    const char *nstr = name ? SL_ConvertToString(name, inst) : nullptr;
    if (nstr && !strcmp(nstr, "power_on"))
        s_powerOn = true;
    // Zombies Checkpoint round restore on "begin_spawning"
    if (g_roundNumberKey && g_zmAutoloadDvar && g_zmAutoloadDvar->current.string[0]
        && nstr && !strcmp(nstr, "begin_spawning"))
    {
        char path[560];
        if (ZmBuildSavePath(g_zmAutoloadDvar->current.string, path, sizeof(path), false)
            && ZmParseCheckpointFile(path, &s_zmCp))
        {
            s_zmCp.phase = 1;
            s_zmCp.spawnTime = level.time;
            unsigned int id = FindVariable(inst, owner, g_roundNumberKey);
            if (id && GetValueType(inst, id) == VAR_INTEGER)
            {
                VariableUnion &value = GetVariableValueAddress(inst, id)->u;
                Com_Printf(16, "zm_load: setting level.round_number %d -> %d\n", value.intValue, s_zmCp.round);
                value.intValue = s_zmCp.round;
            }
            if (s_zmCp.round > 1 && g_firstRoundKey)
            {
                unsigned int fid = FindVariable(inst, owner, g_firstRoundKey);
                if (fid && GetValueType(inst, fid) == VAR_INTEGER)
                    GetVariableValueAddress(inst, fid)->u.intValue = 0;
            }
            if (s_zmCp.round > 1 && g_zombieMoveSpeedKey)
            {
                unsigned int mid = FindVariable(inst, owner, g_zombieMoveSpeedKey);
                if (mid && GetValueType(inst, mid) == VAR_INTEGER)
                    GetVariableValueAddress(inst, mid)->u.intValue = s_zmCp.round * 8;
            }
        }
        Dvar_SetString((dvar_s *)g_zmAutoloadDvar, "");
    }
    if (Sys_IsHeadless() && Dvar_GetBool("bo1_testclient"))
        G_SP_HeadlessDogsNotify(inst, owner, name);
    if (!g_roundNumberKey || !g_headlessRound || g_headlessRound->current.integer <= 0 || !Sys_IsHeadless()
        || !Dvar_GetBool("bo1_testclient"))
        return;
    if (!name || strcmp(SL_ConvertToString(name, inst), "begin_spawning"))
        return;
    unsigned int id = FindVariable(inst, owner, g_roundNumberKey);
    if (!id || GetValueType(inst, id) != VAR_INTEGER)
        return;
    VariableUnion &value = GetVariableValueAddress(inst, id)->u;
    Com_Printf(16, "TEST SWITCH bo1_testclient_round: level.round_number %d -> %d\n",
        value.intValue, g_headlessRound->current.integer);
    value.intValue = g_headlessRound->current.integer;
}
