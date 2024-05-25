local e={}
e.inited = false
e.divisions={1,2,4,6,8,12,16}
e.division_names={"2 wn","wn","hn","hn-t","qn","qn-t","eighth"}
e.param_list={
  "lfos","param_value","config_lfo","config_lfo_status",
  "lfo_period","lfo_range_min_number","lfo_range_min_control","lfo_range_max_number","lfo_range_max_control","grain_params",
  "overtoneslfo","subharmonicslfo","cutofflfo","sizelfo","densitylfo","speedlfo","volumelfo","spread_panlfo","spread_siglfo","jitterlfo",
  "overtones","subharmonics","spread_sig_offset1","spread_sig_offset2","spread_sig_offset3","spread_pan","jitter","spread_sig","size",
  "pos","q","division","speed","send","q","cutoff","decay_shape","attack_shape","decay_time","attack_time","attack_level","fade","pitch","density","pan","volume","seek","play"}
e.param_list_delay={"delay_volume","delay_mod_freq","delay_mod_depth","delay_fdbk","delay_diff","delay_damp","delay_size","delay_time"}
e.num_voices=2
e.num_scenes=2
e.active_scenes={1,1}

-- here's a version that handles recursive tables here:
--  http://lua-users.org/wiki/CopyTable
function deep_copy(orig, copies)
  copies = copies or {}
  local orig_type = type(orig)
  local copy
  if orig_type == 'table' then
      if copies[orig] then
          copy = copies[orig]
      else
          copy = {}
          copies[orig] = copy
          for orig_key, orig_value in next, orig, nil do
              copy[deep_copy(orig_key, copies)] = deep_copy(orig_value, copies)
          end
          setmetatable(copy, deep_copy(getmetatable(orig), copies))
      end
  else -- number, string, boolean, etc
      copy = orig
  end
  return copy
end

function e:init(sample_selected_callback)
  self.sample_selected_callback = sample_selected_callback
end

function e:on_sample_selected(voice,file)
  self.sample_selected_callback(voice,file)
end

function e:bang(scene, bangscope)
  bangscope = bangscope or 1
  if bangscope < 3 then
    for i=1,e.num_voices do
      for _,param_name in ipairs(e.param_list) do
        local p=params:lookup_param(i..param_name..scene)
        if p.t~=6 then p:bang() end
      end
      -- local p=params:lookup_param(i.."pattern"..scene)
      -- p:bang()
    end
  end
  if bangscope ~= 2 then
    for _,param_name in ipairs(e.param_list_delay) do
      local p=params:lookup_param(param_name..scene)
      if p.t~=6 then p:bang() end
    end
  end
end

function e:get_gr_env_values(voice, scene)
  local attack_level = params:get(voice.."attack_level"..scene)
  local attack_time = params:get(voice.."attack_time"..scene)
  local decay_time = params:get(voice.."decay_time"..scene)
  local attack_shape = params:get(voice.."attack_shape"..scene)
  local decay_shape = params:get(voice.."decay_shape"..scene)
  return {attack_level, attack_time, decay_time, attack_shape, decay_shape}
end

-- lfo stuff

-- lfo refreshing
e.lfo_refresh=metro.init()
e.lfo_refresh.time=0.1
e.lfo_refresh.event=function()
  e:update_lfos() -- use this metro to update lfos
end
 
mod_parameters={
  {p_type="control",id="size",name="size",range={0.2,4,0.2,4},lfo=24/58},
  {p_type="number",id="density",name="density",range={3,16,3,16},lfo=16/24},
  {p_type="control",id="speed",name="speed",range={-2.0,2.0,-2.0,2.0},lfo=16/24},
  {p_type="number",id="jitter",name="jitter",range={15,200,15,200},lfo=32/64},
  {p_type="control",id="volume",name="volume",range={0,1,0,1},lfo=16/24},
  {p_type="number",id="spread_pan",name="spread pan",range={0,100,0,100},lfo=16/24},
  {p_type="number",id="spread_sig",name="spread sig",range={0,500,0,500},lfo=16/24},
  {p_type="number",id="cutoff",name="filter cutoff",range={500,2000,500,2000},lfo=16/24},
  {p_type="control",id="subharmonics",name="subharmonics",range={0,1,0,1},lfo=24/70},
  {p_type="control",id="overtones",name="overtones",range={0,0.2,0,0.2},lfo=36/60},
}


mod_param_names={}
for i,mod in ipairs(mod_parameters) do
  table.insert(mod_param_names,mod.name)
end

e.mod_param_vals={}
e.mod_params_dyn={}
e.active_mod_param_ix={}

for i=1,e.num_voices do
  e.mod_params_dyn[i]={}
  e.active_mod_param_ix[i]={}
  for j=1,e.num_scenes do
    e.active_mod_param_ix[i][j]=1
    e.mod_params_dyn[i][j]=deep_copy(mod_parameters)
  end
end
dyn=e.mod_params_dyn

function e:update_lfos()
  if e.inited == true then
    for i=1,e.num_voices do
      e.mod_param_vals[i]={}
      for j=1,e.num_scenes do
        e.mod_param_vals[i][j]={}
        for k,mod in ipairs(e.mod_params_dyn[i][j]) do
          local range={mod.range[3],mod.range[4]}
          local period=params:get(i.."lfo_period"..j)
          e.mod_param_vals[i][j][k]={id=mod.id,minmax=minmax,range=range,period=period,offset=1}--math.random()*30}

          local active_ix=e.active_mod_param_ix[i][j]
          if k==active_ix then
            local lfo_val = params:get(i..mod.id..j)
            params:set(i.."param_value"..j,lfo_val)
          end
        end
      end
      local scene=params:get(i.."scene")
      if params:get(i.."play"..scene)==2 then
        for j,k in ipairs(e.mod_param_vals[i][scene]) do
          if params:get(i..k.id.."lfo"..scene)==2 then
            local lfo_raw_val=self:calculate_lfo(k.period,k.offset)
            local lfo_scaled_val=util.clamp(
              util.linlin(
                -1,1,
                k.range[1],
                k.range[2],
                lfo_raw_val
              ), 
              k.range[1],k.range[2]
            )
            params:set(i..k.id..scene,lfo_scaled_val)
          end
        end
      end
    end
  end
end


function e:calculate_lfo(period_in_beats,offset)
  if period_in_beats==0 then
    return 1
  else
    local lfo_calc=math.sin(2*math.pi*clock.get_beats()/period_in_beats+offset)
    return lfo_calc
  end
end

-- param stuff
function e:rebuild_params()
  if _menu.rebuild_params~=nil then
    _menu.rebuild_params()
  end
end

function e:load_file(voice,scene,file)
  engine.read(voice,file)
  e:on_sample_selected(voice,scene,file)
  params:set(voice.."play"..scene,2)
end
function e:setup_params()
  params:add_separator("granular")
  local old_volume={0.25,0.25,0.25,0.25}
  
  for i=1,e.num_voices do
    params:add_group("sample "..i,(#e.param_list*2)-2)
    params:add_option(i.."scene","scene",{"a","b"},1)
    params:set_action(i.."scene",function(scene)
      e.active_scenes[i]=scene
      for _,param_id in ipairs(e.param_list) do
        params:hide(i..param_id..(3-scene))
        params:show(i..param_id..scene)
        -- local p=params:lookup_param(i..param_id..scene)
        -- p:bang()
        local lfo_ix = e.active_mod_param_ix[i][scene]
        local lfo = mod_parameters[lfo_ix]
        if lfo.p_type=="number" then
          params:hide(i.."lfo_range_min_control"..scene)
          params:hide(i.."lfo_range_max_control"..scene)
        else
          params:hide(i.."lfo_range_min_number"..scene)
          params:hide(i.."lfo_range_max_number"..scene)
        end

        -- e:rebuild_params()
      end

      -- is this one needed????
      e:bang(scene)

      
      -- local p=params:lookup_param(i.."pattern"..scene)
      -- p:bang()
      -- if params:get(i.."pattern"..scene)=="" or params:get(i.."pattern"..scene)=="[]" then
        -- granchild_grid:toggle_playing_voice(i,false)
      -- end
      e:rebuild_params()
    end)
    local sample_modes={"live","recorded"}
    params:add_option(i.."sample_mode","sample mode",sample_modes,1)
    params:set_action(i.."sample_mode",function(mode)
      local function callback_func()
        if sample_modes[mode]=="live" then
          print("gran live",i)
          osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/granulate_live",{i-1})
        elseif sample_modes[mode]=="recorded" then
          local recorded_file = params:get(i.."sample")
          if recorded_file ~= "-" then
            print("gran recorded",i,recorded_file)
            e:load_file(i,e.active_scenes[i],recorded_file)
            -- params:set(i.."sample"..e.active_scenes[i],recorded_file)
            -- self.sample_selected_callback(i,recorded_file)
          else
            print("no file selected to granulate")
            for scene=1, e.num_scenes do 
              params:set(i.."play"..scene,1)
            end
          end
        end
      end
      clock.run(enc_debouncer,callback_func)
    end)
    params:add_file(i.."sample","sample")
    params:set_action(i.."sample",function(file)
      print("sample ",i,e.active_scenes[i],file)
      if file~="-" then
        e:load_file(i,e.active_scenes[i],file)
        params:set(i.."play"..e.active_scenes[i],2)

        -- clock.run(load_waveform,file)

        -- e:on_sample_selected(i,file)
        -- engine.read(i,file)
        -- params:set(i.."play"..scene,2)

        -- load for other scene by default
        -- if params:get(i.."sample"..(3-scene))=="-" then
        --   params:set(i.."sample"..(3-scene),file,true)
        --   params:set(i.."play"..(3-scene),2,true)
        -- end
      end
    end)
    for scene=1,2 do

      params:add_separator(i.."lfos"..scene,"lfos")
      params:add_option(i.."config_lfo"..scene,"lfo name",mod_param_names,1)
      params:set_action(i.."config_lfo"..scene,function(value) 
        e.active_mod_param_ix[i][scene] = value
        local range_min, range_max
        local mod_type=mod_parameters[value].p_type
        local range_min_val = e.mod_params_dyn[i][scene][value].range[1]
        local range_max_val = e.mod_params_dyn[i][scene][value].range[2]
        if mod_type == "number" then 
          params:show(i.."lfo_range_min_number"..scene) 
          params:show(i.."lfo_range_max_number"..scene) 
          params:hide(i.."lfo_range_min_control"..scene)
          params:hide(i.."lfo_range_max_control"..scene)
          
          range_min=params:lookup_param(i.."lfo_range_min_number"..scene)
          range_max=params:lookup_param(i.."lfo_range_max_number"..scene)
          
          range_min.name=mod_param_names[value].." range min"
          range_max.name=mod_param_names[value].." range max"
          print(i,scene,value,range_min.min,range_min.max)
          range_min.min=range_min_val
          range_min.max=range_max_val
          range_max.min=range_min_val
          range_max.max=range_max_val    
        else
          params:show(i.."lfo_range_min_control"..scene) 
          params:hide(i.."lfo_range_min_number"..scene)
          params:show(i.."lfo_range_max_control"..scene) 
          params:hide(i.."lfo_range_max_number"..scene)
          
          range_min=params:lookup_param(i.."lfo_range_min_control"..scene)
          range_max=params:lookup_param(i.."lfo_range_max_control"..scene)
          range_min.name=mod_param_names[value].." range min"
          range_max.name=mod_param_names[value].." range max"
          
          range_min.controlspec.minval=range_min_val
          range_min.controlspec.maxval=range_max_val
          range_max.controlspec.minval=range_min_val
          range_max.controlspec.maxval=range_max_val
        end
        local min_range_default = e.mod_params_dyn[i][scene][value].range[3]
        -- min_range_default = min_range_default == nil and e.mod_params_dyn[i][scene][value].range[1] or min_range_default
        if mod_type == "number" then 
          params:set(i.."lfo_range_min_number"..scene,min_range_default)
        else
          params:set(i.."lfo_range_min_control"..scene,min_range_default)
        end

        local max_range_default = e.mod_params_dyn[i][scene][value].range[4]
        -- max_range_default = max_range_default == nil and e.mod_params_dyn[i][scene][value].range[2] or max_range_default
        
        if mod_type == "number" then 
          params:set(i.."lfo_range_max_number"..scene,max_range_default)
        else
          params:set(i.."lfo_range_max_control"..scene,max_range_default)
        end



        local value_param=params:lookup_param(i.."param_value"..scene)
        local status_param=params:lookup_param(i.."config_lfo_status"..scene)
        local period_param=params:lookup_param(i.."lfo_period"..scene)
        value_param.name=mod_param_names[value].." value"
        status_param.name=mod_param_names[value].." lfo status"
        period_param.name=mod_param_names[value].." lfo period"
        
        -- print(i,scene,value,"min_range_default,max_range_default",min_range_default,max_range_default)
        local selected_lfo=i..mod_parameters[e.active_mod_param_ix[i][scene]].id.."lfo"..scene
        params:set(i.."config_lfo_status"..scene,params:get(selected_lfo))
        
        local lfo_period=e.mod_params_dyn[i][scene][value].lfo
        params:set(i.."lfo_period"..scene,lfo_period)
        e:rebuild_params()
      end)
      params:add_control(i.."param_value"..scene,mod_param_names[1].." value",controlspec.new(-100000,100000))
      
      params:add_option(i.."config_lfo_status"..scene,mod_param_names[1].." lfo status",{"off","on"},1)
      params:set_action(i.."config_lfo_status"..scene,function(value) 
        params:set(i..mod_parameters[e.active_mod_param_ix[i][scene]].id.."lfo"..scene,value)
      end)
      
      params:add_number(i.."lfo_period"..scene,mod_param_names[1].." lfo period",1,200,50)
      params:set_action(i.."lfo_period"..scene,function(value) 
        local ix = e.active_mod_param_ix[i][scene];
        local dyn_mod_param = e.mod_params_dyn[i][scene][ix]
        dyn_mod_param.lfo=value
      end)
      params:add_number(i.."lfo_range_min_number"..scene,mod_param_names[1].." range min",15,200,15)
      params:set_action(i.."lfo_range_min_number"..scene,function(value) 
        local min=i.."lfo_range_min_number"..scene
        local max=i.."lfo_range_max_number"..scene
        local max_val=params:get(max)
        if value > max_val then
          params:set(min,max_val)
        end
        local ix = e.active_mod_param_ix[i][scene];
        local dyn_mod_param = e.mod_params_dyn[i][scene][ix]
        if e.inited == true then
          dyn_mod_param.range[3]=value 
        end
      end)
      
      params:add_number(i.."lfo_range_max_number"..scene,mod_param_names[1].." range max",15,200,200)
      params:set_action(i.."lfo_range_max_number"..scene,function(value) 
        local min=i.."lfo_range_min_control"..scene
        local max=i.."lfo_range_max_control"..scene
        local min_val=params:get(min)
        if value < min_val then
          params:set(max,min_val)
        end

        local ix = e.active_mod_param_ix[i][scene];
        local dyn_mod_param = e.mod_params_dyn[i][scene][ix]
        if e.inited == true then 
          -- print("set max",i,scene,value)
          dyn_mod_param.range[4]=value 
        end
      end)


      params:add_control(i.."lfo_range_min_control"..scene,mod_param_names[1].." range min",controlspec.new(0,0.25,"lin",0.01,0))
      params:set_action(i.."lfo_range_min_control"..scene,function(value) 
        local min=i.."lfo_range_min_control"..scene
        local max=i.."lfo_range_max_control"..scene
        local max_val=params:get(max)
        if value > max_val then
          params:set(min,max_val)
        end

        local ix = e.active_mod_param_ix[i][scene];
        local dyn_mod_param = e.mod_params_dyn[i][scene][ix]
        if e.inited == true then
          dyn_mod_param.range[3]=value
        end

      end)
      
      params:add_control(i.."lfo_range_max_control"..scene,mod_param_names[1].." range max",controlspec.new(0,0.25,"lin",0.01,0))
      params:set_action(i.."lfo_range_max_control"..scene,function(value) 
        local min=i.."lfo_range_min_control"..scene
        local max=i.."lfo_range_max_control"..scene
        local min_val=params:get(min)
        if value < min_val then
          params:set(max,min_val)
        end

        local ix = e.active_mod_param_ix[i][scene];
        local dyn_mod_param = e.mod_params_dyn[i][scene][ix]
        if e.inited == true then
          dyn_mod_param.range[4]=value
        end
      end)
      

      params:add_separator(i.."grain_params"..scene,"param values")
      


      params:add_option(i.."play"..scene,"play",{"off","on"},1)
      -- params:add_option(i.."play"..scene,"play",{"off","on"},1)
      params:set_action(i.."play"..scene,function(x) engine.gate(i,x-1) end)

      params:add_control(i.."volume"..scene,"volume",controlspec.new(0,1.0,"lin",0.05,1,"vol",0.05/1))
      -- params:add_control(i.."volume"..scene,"volume",controlspec.new(0,1.0,"lin",0.05,0.25,"vol",0.05/1))
      params:set_action(i.."volume"..scene,function(value)
        engine.volume(i,value)
        -- turn off the delay if volume is zero
        if value==0 then
          engine.send(i,0)
        elseif value>0 and old_volume[i]==0 then
          engine.send(i,params:get(i.."send"..scene))
        end
        old_volume[i]=value
      end)
      params:add_option(i.."volumelfo"..scene,"volume lfo",{"off","on"},1)

      params:add_control(i.."speed"..scene,"speed",controlspec.new(-2.0,2.0,"lin",0.01,0.1,"",0.01/1))
      -- params:add_control(i.."speed"..scene,"speed",controlspec.new(-2.0,2.0,"lin",0.1,0,"",0.1/4))
      params:set_action(i.."speed"..scene,function(value) engine.speed(i,value) end)
      params:add_option(i.."speedlfo"..scene,"speed lfo",{"off","on"},1)
      params:add_control(i.."seek"..scene,"seek",controlspec.new(0,1,"lin",0.001,0,"",0.001/1,true))
      params:set_action(i.."seek"..scene,function(value) engine.seek(i,util.clamp(value+params:get(i.."pos"..scene),0,1)) end)
      params:add_control(i.."pos"..scene,"pos",controlspec.new(-1/40,1/40,"lin",0.001,0))
      params:set_action(i.."pos"..scene,function(value) engine.seek(i,util.clamp(value+params:get(i.."seek"..scene),0,1)) end)

      params:add_control(i.."size"..scene,"size",controlspec.new(0.1,15,"exp",0.01,1,"",0.01/1))
      params:set_action(i.."size"..scene,function(value)
        engine.size(i,util.clamp(value*clock.get_beat_sec()/10,0.001,util.linlin(1,40,1,0.1,params:get(i.."density"..scene))))
      end)
      params:add_option(i.."sizelfo"..scene,"size lfo",{"off","on"},1)

      params:add_taper(i.."spread_sig"..scene,"spread sig",0,500,0,5,"ms")
      params:set_action(i.."spread_sig"..scene,function(value) engine.spread_sig(i,value/1000) end)
      params:add_option(i.."spread_siglfo"..scene,"spread sig lfo",{"off","on"},1)
      
      params:add_taper(i.."spread_sig_offset1"..scene,"spread sig offset 1",0,500,0,5,"ms")
      params:set_action(i.."spread_sig_offset1"..scene,function(value) engine.spread_sig_offset1(i,value/1000) end)
      
      params:add_taper(i.."spread_sig_offset2"..scene,"spread sig offset 2",0,500,0,5,"ms")
      params:set_action(i.."spread_sig_offset2"..scene,function(value) engine.spread_sig_offset2(i,value/1000) end)
      
      params:add_taper(i.."spread_sig_offset3"..scene,"spread sig offset 3",0,500,0,5,"ms")
      params:set_action(i.."spread_sig_offset3"..scene,function(value) engine.spread_sig_offset3(i,value/1000) end)
      
      params:add_taper(i.."jitter"..scene,"jitter",0,500,0,5,"ms")
      params:set_action(i.."jitter"..scene,function(value) engine.jitter(i,value/1000) end)
      params:add_option(i.."jitterlfo"..scene,"jitter lfo",{"off","on"},1)

      params:add_control(i.."density"..scene,"density",controlspec.new(1,40,"lin",1,12,"/beat",1/40))
      params:set_action(i.."density"..scene,function(value) engine.density(i,value/(clock.get_beat_sec())) end)
      -- params:set_action(i.."density"..scene,function(value) engine.density(i,value/(4*clock.get_beat_sec())) end)
      params:add_option(i.."densitylfo"..scene,"density lfo",{"off","on"},1)

      -- update clock tempo param to reset density 
      -- local old_tempo_action=params:lookup_param("clock_tempo").action
      -- local tempo=params:lookup_param("clock_tempo")
      -- tempo.action = function ()
        
      --   old_tempo_action()
      -- end


      params:add_control(i.."pitch"..scene,"pitch",controlspec.new(-48,48,"lin",1,0,"note",1/96))
      params:set_action(i.."pitch"..scene,function(value) engine.pitch(i,math.pow(0.5,-value/12)) end)

      params:add_taper(i.."fade"..scene,"att / dec",1,9000,1000,3,"ms")
      params:set_action(i.."fade"..scene,function(value) engine.envscale(i,value/1000) end)

      params:add_control(i.."attack_level"..scene,"attack level",controlspec.new(0,1,"lin",0.01,1,"",0.01/1))
      params:set_action(i.."attack_level"..scene,function(value) 
        engine.gr_envbuf(i,table.unpack(e:get_gr_env_values(i,scene))) 
      end)

      params:add_control(i.."attack_time"..scene,"attack time",controlspec.new(0.01,1,"lin",0.01,0.5,"",0.001/1))
      params:set_action(i.."attack_time"..scene,function(value) 
        if params:get(i.."decay_time"..scene) ~= 1-value then
          params:set(i.."decay_time"..scene,1-value) 
        end
        engine.gr_envbuf(i,table.unpack(e:get_gr_env_values(i,scene))) 
      end)
      
      params:add_control(i.."decay_time"..scene,"decay time",controlspec.new(0.01,1,"lin",0.01,0.5,"",0.001/1))
      params:set_action(i.."decay_time"..scene,function(value) 
        if params:get(i.."attack_time"..scene) ~= 1-value then
          params:set(i.."attack_time"..scene,1-value) 
        end
        engine.gr_envbuf(i,table.unpack(e:get_gr_env_values(i,scene))) 
      end)

      -- params:add_control(i.."attack_shape"..scene,"attack shape",controlspec.new(-8,8,"lin",0.01,8,"",0.01/1))
      -- local prev

      --hold/step no work
      params:add_option(i.."attack_shape"..scene,"attack shape",{"step","lin","sin","wel","squared","cubed"},4)
      params:set_action(i.."attack_shape"..scene,function(value) 
        engine.gr_envbuf(i,table.unpack(e:get_gr_env_values(i,scene))) 
      end)
      
      -- params:add_control(i.."decay_shape"..scene,"decay shape",controlspec.new(-8,8,"lin",0.01,-8,"",0.01/1))
      params:add_option(i.."decay_shape"..scene,"decay shape",{"step","lin","exp","sin","wel","squared","cubed"},5)
      params:set_action(i.."decay_shape"..scene,function(value) 
        engine.gr_envbuf(i,table.unpack(e:get_gr_env_values(i,scene))) 
      end)

      
      
      params:add_control(i.."cutoff"..scene,"filter cutoff",controlspec.new(20,20000,"exp",0,20000,"hz"))
      params:set_action(i.."cutoff"..scene,function(value) engine.cutoff(i,value) end)
      params:add_option(i.."cutofflfo"..scene,"filter cutoff lfo",{"off","on"},1)
      
      params:add_control(i.."q"..scene,"filter rq",controlspec.new(0.01,1.0,"exp",0.01,0.1,"",0.01/1))
      params:set_action(i.."q"..scene,function(value) engine.q(i,value) end)

      params:add_control(i.."send"..scene,"delay send",controlspec.new(0.0,1.0,"lin",0.01,0.2))
      params:set_action(i.."send"..scene,function(value) engine.send(i,value) end)


      params:add_option(i.."division"..scene,"division",e.division_names,5)
      params:set_action(i.."division"..scene,function(value)
        -- if granchild_grid~=nil then
        --   granchild_grid:set_division(i,e.divisions[value])
        -- end
      end)

      params:add_control(i.."pan"..scene,"pan",controlspec.new(-1,1,"lin",0.01,0,"",0.01/1))
      params:set_action(i.."pan"..scene,function(value) engine.pan(i,value) end)

      params:add_taper(i.."spread_pan"..scene,"spread pan",0,100,0,0,"%")
      params:set_action(i.."spread_pan"..scene,function(value) engine.spread_pan(i,value/100) end)
      params:add_option(i.."spread_panlfo"..scene,"spread pan lfo",{"off","on"},2)

      params:add_control(i.."subharmonics"..scene,"subharmonic vol",controlspec.new(0.00,1.00,"lin",0.01,0))
      params:set_action(i.."subharmonics"..scene,function(value) engine.subharmonics(i,value) end)
      params:add_option(i.."subharmonicslfo"..scene,"subharmonic lfo",{"off","on"},1)

      params:add_control(i.."overtones"..scene,"overtone vol",controlspec.new(0.00,1.00,"lin",0.01,0))
      params:set_action(i.."overtones"..scene,function(value) engine.overtones(i,value) end)
      params:add_option(i.."overtoneslfo"..scene,"overtone lfo",{"off","on"},1)

      -- params:add_text(i.."pattern"..scene,"pattern","")
      -- params:hide(i.."pattern"..scene)
      -- params:set_action(i.."pattern"..scene,function(value)
        -- if granchild_grid~=nil then
        --   granchild_grid:set_steps(i,value)
        -- end
      -- end)
    end
  end

  params:add_group("delay",17)
  params:add_option("delayscene","scene",{"a","b"},1)
  params:set_action("delayscene",function(scene)
    -- for _,param_name in ipairs(e.param_list_delay) do
    --   params:hide(i..param_name..(3-scene))
    --   params:show(i..param_name..scene)
    --   local p=params:lookup_param(i..param_name..scene)
    --   p:bang()
    -- end
    e:bang(scene,3)
  end)
  for scene=1,2 do
    -- effect controls
    -- delay time
    params:add_control("delay_time"..scene,"*".."delay time",controlspec.new(0.0,60.0,"lin",.01,2.00,""))
    params:set_action("delay_time"..scene,function(value) engine.delay_time(value) end)
    -- delay size
    params:add_control("delay_size"..scene,"*".."delay size",controlspec.new(0.5,5.0,"lin",0.01,2.00,""))
    params:set_action("delay_size"..scene,function(value) engine.delay_size(value) end)
    -- dampening
    params:add_control("delay_damp"..scene,"*".."delay damp",controlspec.new(0.0,1.0,"lin",0.01,0.10,""))
    params:set_action("delay_damp"..scene,function(value) engine.delay_damp(value) end)
    -- diffusion
    params:add_control("delay_diff"..scene,"*".."delay diff",controlspec.new(0.0,1.0,"lin",0.01,0.707,""))
    params:set_action("delay_diff"..scene,function(value) engine.delay_diff(value) end)
    -- feedback
    params:add_control("delay_fdbk"..scene,"*".."delay fdbk",controlspec.new(0.00,1.0,"lin",0.01,0.20,""))
    params:set_action("delay_fdbk"..scene,function(value) engine.delay_fdbk(value) end)
    -- mod depth
    params:add_control("delay_mod_depth"..scene,"*".."delay mod depth",controlspec.new(0.0,1.0,"lin",0.01,0.00,""))
    params:set_action("delay_mod_depth"..scene,function(value) engine.delay_mod_depth(value) end)
    -- mod rate
    params:add_control("delay_mod_freq"..scene,"*".."delay mod freq",controlspec.new(0.0,10.0,"lin",0.01,0.10,"hz"))
    params:set_action("delay_mod_freq"..scene,function(value) engine.delay_mod_freq(value) end)
    -- delay output volume
    params:add_control("delay_volume"..scene,"*".."delay output volume",controlspec.new(0.0,1.0,"lin",0,1.0,""))
    params:set_action("delay_volume"..scene,function(value) engine.delay_volume(value) end)
  end
  -- params:add_control("rec_fade","rec fade time",controlspec.new(0.0,1500,"lin",10,100,"ms",10/1500))

  -- hide scene 2 initially
  for i=1,e.num_voices do
    for _,param_name in ipairs(e.param_list) do
      params:hide(i..param_name.."2")
    end
    for j=1,e.num_scenes do
      if mod_parameters[1].p_type=="number" then
        params:hide(i.."lfo_range_min_control"..j)
        params:hide(i.."lfo_range_max_control"..j)        
      else
        params:hide(i.."lfo_range_min_number"..j)
        params:hide(i.."lfo_range_max_number"..j)
      end
    end
  end
  for _,param_name in ipairs(e.param_list_delay) do
    params:hide(param_name.."2")
  end

  self:bang(1)
  -- params:bang()

  --hack to get the lfo config min/max params correct  
  params:set("1config_lfo2",2)
  params:set("1config_lfo2",1)
  params:set("1scene",2)
  params:set("1scene",1)

  e.inited=true
  self.lfo_refresh:start()
  
end

function e:cleanup()
  print("eglut cleanup")
  if e.lfo_refresh then metro.free(e.lfo_refresh) end
end
return e