local e={}

divisions={1,2,4,6,8,12,16}
division_names={"2 wn","wn","hn","hn-t","qn","qn-t","eighth"}
param_list={"overtones","overtoneslfo","subharmonics","subharmonicslfo","sizelfo","densitylfo","speedlfo","volumelfo","spreadlfo","spread_sig_lfo","jitterlfo","spread_sig_offset1","spread_sig_offset2","spread_sig_offset3","spread","jitter","spread_sig","size","pos","q","division","speed","send","q","cutoff","decay_shape","attack_shape","decay_time","attack_time","attack_level","fade","pitch","density","pan","volume","seek","play","selected_gslice","sample"}
param_list_delay={"delay_volume","delay_mod_freq","delay_mod_depth","delay_fdbk","delay_diff","delay_damp","delay_size","delay_time"}
num_samples=1
  
function e:bang(scene)
  for i=1,num_samples do
    for _,param_name in ipairs(param_list) do
      local p=params:lookup_param(i..param_name..scene)
      p:bang()
    end
    local p=params:lookup_param(i.."pattern"..scene)
    p:bang()
  end
  for _,param_name in ipairs(param_list_delay) do
    local p=params:lookup_param(param_name..scene)
    p:bang()
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

function e:update_gslices(voice, scene)
  p=params:lookup_param(voice.."selected_gslice"..scene)
  if #gslices < 3 then
    params:hide(voice.."selected_gslice"..scene)
    params:hide(voice.."remove_selected_gslice"..scene)
  else
    p.max=math.floor((#gslices+1)/2)
    if p.value > p.max then p.value=p.max end
    params:show(voice.."selected_gslice"..scene)
    params:show(voice.."remove_selected_gslice"..scene)
  end
  e:rebuild_params()
end

function e:rebuild_params()
  if _menu.rebuild_params~=nil then
    _menu.rebuild_params()
  end
end

function e:setup_params()
  params:add_separator("granular")
  params:add_number("selected_sample","selected sample",1,num_samples,1)
  local old_volume={0.25,0.25,0.25,0.25}
  for i=1,num_samples do
    params:add_group("sample "..i,62)
    params:add_option(i.."scene","scene",{"a","b"},1)
    params:set_action(i.."scene",function(scene)
      for _,param_name in ipairs(param_list) do
        e:update_gslices(i, scene)
        params:hide(i..param_name..(3-scene))
        params:show(i..param_name..scene)
        local p=params:lookup_param(i..param_name..scene)
        p:bang()
      end
      local p=params:lookup_param(i.."pattern"..scene)
      p:bang()
      -- if params:get(i.."pattern"..scene)=="" or params:get(i.."pattern"..scene)=="[]" then
        -- granchild_grid:toggle_playing_voice(i,false)
      -- end
      e:rebuild_params()
    end)
    for scene=1,2 do
      params:add_file(i.."sample"..scene,"sample")
      params:set_action(i.."sample"..scene,function(file)
        print("sample "..file)
        if file~="-" then
          clock.run(load_waveform,file)
          engine.read(i,file)
          params:set(i.."play"..scene,2)
          if params:get(i.."sample"..(3-scene))=="-" then
            -- load for other scene by default
            params:set(i.."sample"..(3-scene),file,true)
            params:set(i.."play"..(3-scene),2,true)
          end
        end
      end)

      params:add_number(i.."selected_gslice"..scene,"selected slice",1,1,1)
      params:set_action(i.."selected_gslice"..scene,function(slice) print("slice",slice) end)
      params:add_trigger(i.."remove_selected_gslice"..scene,"remove selected slice")
      params:set_action(i.."remove_selected_gslice"..scene,function() 
        local selected_gslice = params:get(i.."selected_gslice"..scene)
        local slice_start = gslices[(selected_gslice*2)-1]
        local slice_end = gslices[selected_gslice*2]
        osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/remove_selected_gslice",{i,selected_gslice-1,slice_start,slice_end})
      end)
      
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

      params:add_control(i.."speed"..scene,"speed",controlspec.new(-2.0,2.0,"lin",0.1,0,"",0.1/4))
      -- params:add_control(i.."speed"..scene,"speed",controlspec.new(-2.0,2.0,"lin",0.1,0,"",0.1/4))
      params:set_action(i.."speed"..scene,function(value) engine.speed(i,value) end)
      params:add_option(i.."speedlfo"..scene,"speed lfo",{"off","on"},1)
      params:add_control(i.."seek"..scene,"seek",controlspec.new(0,1,"lin",0.001,0,"",0.001/1,true))
      params:set_action(i.."seek"..scene,function(value) engine.seek(i,util.clamp(value+params:get(i.."pos"..scene),0,1)) end)
      params:add_control(i.."pos"..scene,"pos",controlspec.new(-1/40,1/40,"lin",0.001,0))
      params:set_action(i.."pos"..scene,function(value) engine.seek(i,util.clamp(value+params:get(i.."seek"..scene),0,1)) end)

      params:add_control(i.."size"..scene,"size",controlspec.new(1,15,"lin",1,5,"",1/15))
      params:set_action(i.."size"..scene,function(value)
        engine.size(i,util.clamp(value*clock.get_beat_sec()/10,0.001,util.linlin(1,40,1,0.1,params:get(i.."density"..scene))))
      end)
      params:add_option(i.."sizelfo"..scene,"size lfo",{"off","on"},1)

      params:add_taper(i.."spread_sig"..scene,"spread sig",0,500,0,5,"ms")
      params:set_action(i.."spread_sig"..scene,function(value) engine.spread_sig(i,value/1000) end)
      params:add_option(i.."spread_sig_lfo"..scene,"spread lfo",{"off","on"},1)
      
      params:add_taper(i.."spread_sig_offset1"..scene,"spread offset 1",-500,500,0,5,"ms")
      params:set_action(i.."spread_sig_offset1"..scene,function(value) engine.spread_sig_offset1(i,value/1000) end)
      
      params:add_taper(i.."spread_sig_offset2"..scene,"spread offset 2",-500,500,0,5,"ms")
      params:set_action(i.."spread_sig_offset2"..scene,function(value) engine.spread_sig_offset2(i,value/1000) end)
      
      params:add_taper(i.."spread_sig_offset3"..scene,"spread offset 3",-500,500,0,5,"ms")
      params:set_action(i.."spread_sig_offset3"..scene,function(value) engine.spread_sig_offset3(i,value/1000) end)
      
      params:add_taper(i.."jitter"..scene,"jitter",0,500,0,5,"ms")
      params:set_action(i.."jitter"..scene,function(value) engine.jitter(i,value/1000) end)
      params:add_option(i.."jitterlfo"..scene,"jitter lfo",{"off","on"},2)

      params:add_control(i.."density"..scene,"density",controlspec.new(1,40,"lin",1,12,"/beat",1/40))
      params:set_action(i.."density"..scene,function(value) engine.density(i,value/(4*clock.get_beat_sec())) end)
      params:add_option(i.."densitylfo"..scene,"density lfo",{"off","on"},1)

      -- update clock tempo param to reset density 
      local old_tempo_action=params:lookup_param("clock_tempo").action
      local tempo=params:lookup_param("clock_tempo")
      tempo.action = function ()
        
        old_tempo_action()
      end


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

      params:add_control(i.."q"..scene,"filter rq",controlspec.new(0.01,1.0,"exp",0.01,1,"",0.01/1))
      params:set_action(i.."q"..scene,function(value) engine.q(i,value) end)

      params:add_control(i.."send"..scene,"delay send",controlspec.new(0.0,1.0,"lin",0.01,0.2))
      params:set_action(i.."send"..scene,function(value) engine.send(i,value) end)


      params:add_option(i.."division"..scene,"division",division_names,5)
      params:set_action(i.."division"..scene,function(value)
        -- if granchild_grid~=nil then
        --   granchild_grid:set_division(i,divisions[value])
        -- end
      end)

      params:add_control(i.."pan"..scene,"pan",controlspec.new(-1,1,"lin",0.01,0,"",0.01/1))
      params:set_action(i.."pan"..scene,function(value) engine.pan(i,value) end)

      params:add_taper(i.."spread"..scene,"spread",0,100,0,0,"%")
      params:set_action(i.."spread"..scene,function(value) engine.spread(i,value/100) end)
      params:add_option(i.."spreadlfo"..scene,"spread lfo",{"off","on"},2)

      params:add_control(i.."subharmonics"..scene,"subharmonic vol",controlspec.new(0.00,1.00,"lin",0.01,0))
      params:set_action(i.."subharmonics"..scene,function(value) engine.subharmonics(i,value) end)
      params:add_option(i.."subharmonicslfo"..scene,"subharmonic lfo",{"off","on"},1)

      params:add_control(i.."overtones"..scene,"overtone vol",controlspec.new(0.00,1.00,"lin",0.01,0))
      params:set_action(i.."overtones"..scene,function(value) engine.overtones(i,value) end)
      params:add_option(i.."overtoneslfo"..scene,"overtone lfo",{"off","on"},1)

      params:add_text(i.."pattern"..scene,"pattern","")
      params:hide(i.."pattern"..scene)
      params:set_action(i.."pattern"..scene,function(value)
        -- if granchild_grid~=nil then
        --   granchild_grid:set_steps(i,value)
        -- end
      end)
    end
  end

  params:add_group("delay",17)
  params:add_option("delayscene","scene",{"a","b"},1)
  params:set_action("delayscene",function(scene)
    for _,param_name in ipairs(param_list_delay) do
      params:hide(i..param_name..(3-scene))
      params:show(i..param_name..scene)
      local p=params:lookup_param(i..param_name..scene)
      p:bang()
    end
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
  params:add_control("rec_fade","rec fade time",controlspec.new(0.0,1500,"lin",10,100,"ms",10/1500))

  -- hide scene 2 initially
  for i=1,num_samples do
    for _,param_name in ipairs(param_list) do
      params:hide(i..param_name.."2")
    end
  end
  for _,param_name in ipairs(param_list_delay) do
    params:hide(param_name.."2")
  end
  self:update_gslices(1,1)
    

  self:bang(1)
end

return e