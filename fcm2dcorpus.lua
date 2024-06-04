-- flucoma 2d corpus explorer
--
-- llllllll.co/t/fcm2dcorpus
--
-- 2d corpus explorer for norns
-- v0.1
--
--    ▼ instructions below ▼
-- k2/k3 navigates the sounds
-- e2 select new sounds

-- adapted from: https://learn.flucoma.org/learn/2d-corpus-explorer/
-- FluCoMa installer code from @infiniteigits (schoolz) graintopia

-- check for requirements
installer_=include("lib/scinstaller/scinstaller")
installer=installer_:new{install_all="true",folder="FluidCorpusManipulation",requirements={"FluidCorpusManipulation"},zip="https://github.com/jaseknighter/flucoma-sc/releases/download/1.0.6-RaspberryPi/FluCoMa-SC-RaspberryPi.zip"}
engine.name=installer:ready() and 'FCM2dCorpus' or nil

-- textentry=require("textentry")

fileselect=include("lib/fileselect")
eglut=include("lib/eglut")
waveform=include("lib/waveform")
gridcontrol=include("lib/gridcontrol")

-- what is this code doing here?????
if not string.find(package.cpath,"/home/we/dust/code/graintopia/lib/") then
  package.cpath=package.cpath..";/home/we/dust/code/graintopia/lib/?.so"
end

json = require "fcm2dcorpus/lib/json/json"

-- modes
--  start
--  loading audio
--  composition loaded
--  analysing
--  points data generated
--  show composition

mode = "start"
-- local composition_loaded = false
-- local analysis_in_progress = false
-- local points_data_generated=false
-- local show_composition = false

local inited=false
local alt_key=false
-- local selecting_file = false
points_data=nil
local cursor_x = 64
local cursor_y = 32
local slice_played_ix = nil
local slice_played_x = nil
local slice_played_y = nil
local transport_src_left_ix = nil
local transport_src_left_x = nil
local transport_src_left_y = nil
local transport_src_right_ix = nil
local transport_src_right_x = nil
local transport_src_right_y = nil

local scale = 30
composition_top = 16
local composition_bottom = 64-16
composition_left = 16
local composition_right = 127-16

local slices_analyzed
local total_slices
local transporting_audio = false
local transport_gate = 1
local enc_debouncing=false

local record_live_duration = 10

waveforms = {}
waveform_names = {"composed","transported"}
waveform_sig_positions = {}
composition_slice_positions = {}
waveform_render_queue={}
-- local waveform_rendering=false

local max_analysis_length = 60 * 3
local gslice_generated=false

local session_name=os.date('%Y-%m-%d-%H%M%S')
local audio_path = _path.audio..norns.state.name.."/"
local session_audio_path=audio_path.."sessions_audio/"..session_name.."/"
local data_path=_path.data..norns.state.name.."/"
local session_data_path=data_path.."sessions_data/"..session_name.."/"
-- local datasets_path=data_path.."datasets/"
--------------------------
-- waveform rendering
--------------------------
function show_waveform(waveform_name)
  for i=1,#waveform_names do
    if waveform_name==waveform_names[i] and waveform_names[i].waveform_samples then
      params:set("show_waveform",i)
    end
  end
end

function waveform_render_queue_add(waveform_name, waveform_path)
  table.insert(waveform_render_queue,{name=waveform_name, path=waveform_path})
  if #waveform_render_queue>0 then
    -- print("waveform_render_queue_add",waveform_name, waveform_path)
    waveforms[waveform_name].load(waveform_path,max_analysis_length)
  end
end

function on_waveform_render(ch, start, i, s)
  if waveform_render_queue and waveform_render_queue[1] then
    local waveform_name=waveform_render_queue[1].name
    -- print("on_waveform_render before,#queue", #waveform_render_queue)
    set_waveform_samples(ch, start, i, s, waveform_name)
    table.remove(waveform_render_queue,1)
    if #waveform_render_queue>0 then
      local next_waveform_name=waveform_render_queue[1].name
      local next_waveform_path=waveform_render_queue[1].path
      waveforms[next_waveform_name].load(next_waveform_path,max_analysis_length)
    else
    end
  end
end

function set_waveform_samples(ch, start, i, s, waveform_name)
  -- local waveform_name=waveform_names[params:get("show_waveform")]
  if waveform_name == "composed" then
    print("set waveform samples composed",s)
    waveforms["composed"]:set_samples(s)
    print("on waveform render composed: mode, s",mode,#s)
  elseif waveform_name == "transported" then
    waveforms["transported"]:set_samples(s)
    print("on waveform render transported: mode, s",mode,#s)
  elseif waveform_name and string.sub(waveform_name,-8) == "gran-rec" then
    waveforms[waveform_name]:set_samples(s)
  else
    for i=1,eglut.num_voices do
      waveforms[i.."gran-live"]:set_samples(s)
      -- if waveform_name == i.."gran-live" then
      --   waveforms[i.."gran-live"]:set_samples(s)
      -- elseif waveform_name == i.."gran-rec" then
      --   waveforms[i.."gran-rec"]:set_samples(s)
      -- end
    end
  end
  screen_dirty = true
end

--------------------------
-- osc functions
--------------------------
local script_osc_event = osc.event

function on_audio_composed(path)
  if mode ~= "audio composed" and mode ~= "analysing" then
    clock.sleep(0.5)
    mode = "analysing composition"
    waveform_render_queue_add("composed",path)
    if params:get("auto_analyze")==2 then
      mode = "analysing"
      screen_dirty = true
      local slice_threshold = params:get('slice_threshold')
      local min_slice_length = params:get('min_slice_length')
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/analyze_2dcorpus",{slice_threshold,min_slice_length})    
    else
      mode = "audio composed"
    end
  end
  -- end
end

function on_transportslice_written(path)
  if mode == "points generated" then
    clock.sleep(0.01)
    waveform_render_queue_add("transported",path)
    print("transport slice waveform loaded")
  end
end

function on_livebuffer_written(path,type)
  clock.sleep(0.01)
  for i=1,eglut.num_voices do
    waveform_render_queue_add(i.."gran-live",path)
  end
  -- waveform_render_queue_add(voice.."gran-live",path)
end

function on_eglut_file_loaded(voice,file)
  print("on_eglut_file_loaded",voice, file)
  if mode~="points generated" then
    mode="granulated"
  end
  waveform_render_queue_add(voice.."gran-rec",file)  
  waveforms[voice.."gran-rec"].load(file,max_analysis_length)  
end

function set_eglut_sample(file,samplenum,scene)
  print("set_eglut_sample",file,samplenum,scene)
  params:set(samplenum.."sample",file)
  clock.sleep(0.1)
  eglut:update_scene(samplenum,scene)
end

function osc.event(path,args,from)
  if script_osc_event then script_osc_event(path,args,from) end
  
  if path == "/lua_eglut/grain_sig_pos" then
    -- tab.print(args)
    local sample=math.floor(args[1]+1)
    table.remove(args,1)
    waveform_sig_positions[sample.."granulated"]=args
    screen_dirty = true
  elseif path == "/lua_fcm2dcorpus/sc_inited" then
    print("fcm 2d corpus sc inited message received")
  elseif path == "/lua_fcm2dcorpus/compose_written" then
    local path = args[1]
    clock.run(on_audio_composed,path)
  elseif path == "/lua_fcm2dcorpus/composelive_written" then
    local path = args[1]
    clock.run(on_audio_composed,path)
  elseif path == "/lua_fcm2dcorpus/analyze_written" then
    print("analysis written", path)
  elseif path == "/lua_fcm2dcorpus/analysis_progress" then
    slices_analyzed = args [1]
    total_slices = args[2]
    screen_dirty = true
  elseif path == "/lua_fcm2dcorpus/analysis_dumped" then
    local json_path=args[1]
    print("dumped to json",json_path)
    clock.run(load_json,json_path)
  elseif path == "/lua_fcm2dcorpus/slice_played" then
    slice_played_ix = tostring(args[1])
    composition_slice_positions[1]=args[2]
    composition_slice_positions[2]=args[3]
    slice_played_ix = args[1]
    slice_played_x = points_data[slice_played_ix][1]
    slice_played_y = points_data[slice_played_ix][2]
    slice_played_x = composition_left + math.ceil(slice_played_x*(127-composition_left-5))
    slice_played_y = composition_top + math.ceil(slice_played_y*(64-composition_top-5))
  elseif path == "/lua_fcm2dcorpus/slice_transported" then
    transport_src_left_ix = tostring(args[1])
    transport_src_right_ix = tostring(args[2])
    transport_src_left_x = points_data[transport_src_left_ix][1]
    transport_src_left_y = points_data[transport_src_left_ix][2]
    transport_src_left_x = composition_left + math.ceil(transport_src_left_x*(127-composition_left-5))
    transport_src_left_y = composition_top + math.ceil(transport_src_left_y*(64-composition_top-5))
    if transport_src_right_ix then
      transport_src_right_x = points_data[transport_src_right_ix][1]
      transport_src_right_y = points_data[transport_src_right_ix][2]
      transport_src_right_x = composition_left + math.ceil(transport_src_right_x*(127-composition_left-5))
      transport_src_right_y = composition_top + math.ceil(transport_src_right_y*(64-composition_top-5))
    end
  elseif path == "/lua_fcm2dcorpus/transportslice_written" then
    path = args[1]
    print("transportslice_written",path)
    clock.run(on_transportslice_written,path)
    for i=1,eglut.num_voices do
      if params:get(i.."granulate_transport")==2 then
        clock.run(set_eglut_sample,path,i,1)
      end
    end
  elseif path == "/lua_fcm2dcorpus/livebuffer_written" then
    local path = args[1]
    local type = args[2]
    clock.run(on_livebuffer_written,path,type)
  elseif path == "/lua_fcm2dcorpus/transport_sig_pos" then
    transport_sig_pos = args[1]
    waveform_sig_positions["transported"]=args
    screen_dirty = true
  end
end

function load_json(json_path)
  clock.sleep(2)
  local data_file=(io.open(json_path, "r"))
  points_data=data_file:read("*all")
  points_data=json.decode(points_data)
  points_data=points_data["data"]
  data_file:close()
  mode = "points generated" 
  local num_points=0
  for k,v in pairs(points_data) do num_points = num_points+1 end
  print("num_points generated",num_points)
  local starting_key = 1
  transport_keys:set_area(starting_key,num_points,4,6,1,1,10,0,1)
  screen_dirty = true
end

--update to save corresponding slice audio (sliceBuf)
--create corresponding trigger to load saved slice data/audio
-- function save_slices_data(name)
--   print("save_slices_data",session_data_path..name)
--   copy_data_file(session_data_path..name,datasets_path..name)
-- end

-- todo: rename to set_composed_audio_path
function set_audio_path(path)
  gslice_generated=false
  composition_slice_positions={}
  print("set_2dcorpus",path)
  -- selecting_file = false
  if path ~= "cancel" then
    mode = "loading audio"
    points_data=nil
    cursor_x = 64
    cursor_y = 32
    transport_src_left_x    = nil
    transport_src_left_y    = nil
    transport_src_right_x   = nil
    transport_src_right_y   = nil
    transport_src_left_ix   = nil
    transport_src_right_ix  = nil


    audio_path = path
    path_type = string.find(audio_path, '/', -1) == #audio_path and "folder" or "file"
    print("path_type",path_type)
    -- os.execute("rm '/temp/normed_fluid_data_set.json'")    
    local max_sample_length = params:get('max_sample_length')
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_2dcorpus",{audio_path,path_type,max_sample_length})
    waveforms["composed"]:set_samples(nil)
    gridcontrol:init()
    -- params:set("selected_sample",1)
    screen_dirty = true
  else
    screen_dirty=true
  end
end


function copy_data_file(from,to)
  local data_path = _path.data..norns.state.name.."/"
  from=data_path..from
  to=data_path..to
  os.execute("cp " .. from .. " " .. to)
end

function create_data_folders()
  os.execute("mkdir -p " .. data_path)
  os.execute("mkdir -p " .. session_data_path)
  os.execute("mkdir -p " .. session_data_path .. "data_sets")
  -- os.execute("mkdir -p " .. datasets_path)
end

function create_audio_folders()
  
  os.execute("mkdir -p " .. audio_path)
  os.execute("mkdir -p " .. session_audio_path)
  os.execute("mkdir -p " .. session_audio_path .. "/transports")
  os.execute("mkdir -p " .. session_audio_path .. "/slice_buffers")
end

function num_files_in_folder(path)
  local files = util.scandir (path)
  return #files
end

function setup_waveforms()
  for i=1,#waveform_names do
    waveforms[waveform_names[i]] = waveform:new({
      name=waveform_names[i],
      composition_top=composition_top,
      composition_bottom=composition_bottom,
      composition_left=composition_left,
      composition_right=composition_right
    })
  end
end

function setup_params()
  params:add_control("live_audio_dry_wet","live audio dry/wet",controlspec.new(0,1,'lin',0.01,1))
  params:set_action("live_audio_dry_wet",function(x)
    print("set live audio gain",x)
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/live_audio_dry_wet",{x})
  end)
  params:add_separator("waveforms")
  params:add_option("show_waveform","show waveform",waveform_names)
  params:set_action("show_waveform",function(x) 
    print("show_waveform",x,waveform_names[x]) 
    if x>2 and x%2~=0 then
      print("write_live_stream_enabled",1)
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/write_live_stream_enabled",{1})  
    else
      print("write_live_stream_enabled",0)
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/write_live_stream_enabled",{0})  
    end
    if waveforms[waveform_names[x]]:get_samples()==nil then
      print("waveform not yet captured")
    end  
  end)
  params:add_separator("slice/transport")
  params:add_control("cursor_x", "cursor x",controlspec.new(0,1,'lin',0.01,0.5,'',0.01))
  params:set_action("cursor_x", function(x)
    cursor_x = util.clamp(x*127,composition_left,127)
    if alt_key == false then play_slice() end
  end)
  params:add_control("cursor_y", "cursor y",controlspec.new(0,1,'lin',0.01,0.5,'',0.01))
  params:set_action("cursor_y", function(x) 
    cursor_y = util.clamp(x*64,composition_top,64)
    if alt_key == false then play_slice() end
  end)

  params:add_group("slice",6+eglut.num_voices)
  --------------------------
  --slice params
  --------------------------
  -- params:add_group("slice",9)
  params:add_trigger("select_folder_file", "select folder/file" )
  params:set_action("select_folder_file", function(x) 
    -- selecting_file = true 
    fileselect.enter(_path.audio, set_audio_path) 
  end)
  params:add_trigger("record_live", "record live")
  params:set_action("record_live", function() 
    mode = "recording"
    screen_dirty = true
    print("record live")
    print("start record live")
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/record_live",{record_live_duration})
  end)
  params:add_option("auto_analyze","auto analyze",{"off","on"},2)
  params:add_control("max_sample_length","max sample length",controlspec.new(1,5,'lin',0.1,1.5,"min"))
  params:add_control("slice_threshold","slice threshold",controlspec.new(0,1,'lin',0.1,0.5))
  params:add_control("min_slice_length","min slice length",controlspec.new(0,100,'lin',0.1,2,"",0.001))
  params:add_control("slice_volume","slice volume",controlspec.new(0,1,'lin',0.1,1))
  params:add_option("show_all_slice_ids","show all slice ids",{"off","on"},1)
  
  --------------------------
  --transport params
  --------------------------
  params:add_group("transport",6+eglut.num_voices)
  params:add_control("transport_volume","transport volume",controlspec.new(0,1,'lin',0.1,1))
  params:set_action("transport_volume", function(vol)         
    show_waveform("transported")
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_volume",{vol}) 
  end)

  params:add_control("transport_rate","transport rate",controlspec.new(-10,10,'lin',0.01,1,"",1/20000))
  -- params:add_control("transport_rate","transport rate",controlspec.new(0.01,10,'lin',0.01,1,"",1/10000))
  params:set_action("transport_rate", function()         
    local transport_rate = params:get('transport_rate')
    show_waveform("transported")
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_rate",{transport_rate}) 
    -- local function callback_func()
    -- end
    -- clock.run(enc_debouncer,callback_func)
  end)


  params:add_control("transport_trig_rate","transport trig rate",controlspec.new(1,40,'lin',1,4,"/beat",1/40))
  params:set_action("transport_trig_rate", function()  
    show_waveform("transported")       
    local function callback_func()
      for voice=1,eglut.num_voices do
        for scene=1,eglut.num_scenes do
          if params:get(voice.."density_sync_external"..scene) == 2 then
            params:set(voice.."density"..scene,params:get('transport_trig_rate'))
          end
        end
      end

      local trig_rate = params:get('transport_trig_rate')/(params:get("transport_beat_divisor")*clock.get_beat_sec())
      print("ttrigrate",trig_rate)

      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_trig_rate",{trig_rate}) 
    end
    clock.run(enc_debouncer,callback_func)
  end)
  params:add_control("transport_beat_divisor","transport beat div",controlspec.new(1,16,'lin',1,4,"",1/16))
  params:set_action("transport_beat_divisor",function() 
    local p=params:lookup_param("transport_trig_rate")
    p:bang()
  end)
  params:add_control("transport_reset_pos","transport reset pos",controlspec.new(0,1,'lin',0,0))
  params:set_action("transport_reset_pos", function()  
    show_waveform("transported")       
    local function callback_func()
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_reset_pos",{params:get('transport_reset_pos')}) 
    end
    clock.run(enc_debouncer,callback_func)
  end)

  params:add_control("transport_stretch","transport stretch",controlspec.new(0,5,'lin',0.01,0,'',1/1000))
  params:set_action("transport_stretch", function()  
    show_waveform("transported")
    local function callback_func()
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_stretch",{params:get('transport_stretch')}) 
    end
    clock.run(enc_debouncer,callback_func)
  end)
  for i=1,eglut.num_voices do
    local default=i==1 and 2 or 1
    params:add_option(i.."granulate_transport","granulate->"..i,{"off","on"},default)
  end
  --------------------------
  --save/load params
  --------------------------
  -- params:add_group("save/load",1)
  -- params:add_trigger("save_slices_data", "save slices data" )
  -- params:set_action("save_slices_data", function(x) 
  --   selecting_file = true 
  --   textentry.enter(save_slices_data) 
  -- end)

end

function enc_debouncer(callback)
  local debounce_time = 0.1
  if enc_debouncing == false then
    enc_debouncing = true
    clock.sleep(debounce_time)
    callback()
    enc_debouncing = false
  end
end

function init()
  print("init>>>")
  if not installer:ready() then
    return
  end
  for i=1,eglut.num_voices do
    table.insert(waveform_names,i.."gran-live")
    table.insert(waveform_names,i.."gran-rec")
  end
  create_audio_folders()
  create_data_folders()
  setup_waveforms()
  setup_params()
  params:set("transport_volume",0.2)
  
  gridcontrol:init()
  eglut:init(on_eglut_file_loaded)
  eglut:setup_params()
  print("eglut inited and params setup")
  -- params:set("1play1",2)

  screen.aa(0)
  softcut.event_render(on_waveform_render)

  redrawtimer = metro.init(function() 
    if (norns.menu.status() == false and fileselect.done~=false) then
      if screen_dirty == true then redraw() end
    end
    -- if norns.menu.status() == true and menu_active == false then
    --   menu_active = true
    -- elseif norns.menu.status() == false and fileselect.done~=false then
    --   if menu_active == true then
    --     menu_active = false
    --     screen_dirty = true
    --   end
    --   redraw()
    -- end
  end, 1/15, -1)
  redrawtimer:start()
  screen_dirty = true
  osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/init_completed",{
      session_name,audio_path,session_audio_path,data_path,session_data_path
  })
  inited=true
end

function key(k,z)
  if not installer:ready() then
    installer:key(k,z)
    do return end
  end
  
  if k==1 then
    if z==1 then
      alt_key=true
    else
      alt_key=false
    end
  end
  if k==2 and z==0 then
    -- fileselect.enter('/home/we/dust/audio', set_audio_path)   
    if alt_key == false then
      params:set("select_folder_file",1)
    elseif mode == "points generated" and slice_played_x then
      local x = math.ceil(util.linlin(composition_left,127,1,127,cursor_x))
      local y = math.ceil(util.linlin(composition_top,64,1,64,cursor_y))    
      transporting_audio = true
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/transport_slices",{x/127,y/64})
      if composition_slice_positions[3] then
        composition_slice_positions[5]=composition_slice_positions[3]
        composition_slice_positions[6]=composition_slice_positions[4]          
      end
      composition_slice_positions[3]=composition_slice_positions[1]
      composition_slice_positions[4]=composition_slice_positions[2]
    end
  elseif k==3 and z==0 then
    if alt_key == true and mode == "start" then
      params:set("record_live",1)
    elseif alt_key == true then
      transport_gate = transport_gate == 1 and 0 or 1
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/transport_gate",{transport_gate})
      print("transport_gate",transport_gate)
    elseif mode == "audio composed" then
      mode = "analysing"
      screen_dirty = true
      local slice_threshold = params:get('slice_threshold')
      local min_slice_length = params:get('min_slice_length')
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/analyze_2dcorpus",{slice_threshold,min_slice_length})
    elseif gslice_generated == true then
        print("append gslice????")
        -- osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/append_gslice",{})
    end      
  end
end

function enc(n,d)
  if not installer:ready() then
    do return end
  end
  if mode == "points generated" and points_data then
    if n==1 then
    elseif n==2 then
      params:set("cursor_x",params:get("cursor_x")+(d/127))
      if alt_key == true and transporting_audio == true then
        local function callback_func()
          local x = math.ceil(util.linlin(composition_left,127,0,127,cursor_x))
          local y = math.ceil(util.linlin(composition_top,64,0,64,cursor_y))      
          osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/transport_x_y",{x/127,y/64})
        end
        clock.run(enc_debouncer,callback_func)
      end
      
    elseif n==3 then
      params:set("cursor_y",params:get("cursor_y")+(d/64))
      if alt_key == true then
        -- engine.volume(1,(y-composition_top)/(64-composition_top-5))
        if transporting_audio == true then 
          local function callback_func()
          local x = math.ceil(util.linlin(composition_left,127,0,127,cursor_x))
          local y = math.ceil(util.linlin(composition_top,64,0,64,cursor_y))      
          osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/transport_x_y",{x/127,y/64})
        end
        clock.run(enc_debouncer,callback_func)
        end
      end
    end
  end
  screen_dirty = true
end

function play_slice()
  local x = math.ceil(util.linlin(composition_left,127,1,127,cursor_x))
  local y = math.ceil(util.linlin(composition_top,64,1,64,cursor_y))
  local retrigger = 0
  osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/play_slice",{x/127,y/64,params:get("slice_volume"),retrigger})
end

-------------------------------
function redraw()
  if skip then
    screen.clear()
    screen.update()
    do return end
  end  

  screen.level(15)

  if not installer:ready() then
    installer:redraw()
    do return end
  end
  if not inited==true then
    print("not yet inited don't redraw")
    do return end
  end
  screen.clear()
  -- draw waveforms
  local show_waveform_name
  local show_waveform_ix = params:get("show_waveform")
  show_waveform_name = waveform_names[show_waveform_ix]
  local show_waveform = waveforms[show_waveform_name]:get_samples()~=nil
  if show_waveform then
    local sig_positions = nil
    if show_waveform_ix-2>0 then
      local voice=math.ceil((show_waveform_ix-2)/eglut.num_voices)
      sig_positions=waveform_sig_positions[voice.."granulated"]
    elseif waveform_names[show_waveform_ix]=="transported" then
      sig_positions=waveform_sig_positions["transported"]
    end
    local slice_pos = show_waveform_name=="composed" and composition_slice_positions or nil
    waveforms[show_waveform_name]:redraw(sig_positions,slice_pos)
  end
  waveforms[show_waveform_name]:display_waveform_frame()
  screen.level(15)  


  -- set data points
  if mode == "points generated" then
    if points_data then
      if slices_analyzed then
        slices_analyzed = nil
        total_slices = nil
      end
      local show_all_slice_ids = params:get("show_all_slice_ids")
      for k,point in pairs(points_data) do 
        -- tab.print(k,v) 
        local x = composition_left + math.ceil(point[1]*(127-composition_left-5))
        local y = composition_top + math.ceil(point[2]*(64-composition_top-5))
        screen.level(15)
        screen.move(x,y-1)
        screen.line_rel(0,3)
        if show_all_slice_ids==2 then
          screen.level(5)
          screen.move(x,y-2)
          screen.text_center(tonumber(k) + 1)
          screen.level(15)
        end
      end
      screen.stroke()
        
    else 
      print("no points data")
    end

    --show slice played
    if (slice_played_ix) then
      -- screen.rect(slice_played_x-1,slice_played_y,2,2)
      -- screen.stroke()
      screen.level(5)
      screen.move(slice_played_x-5,slice_played_y-9)
      screen.rect(slice_played_x-5,slice_played_y-9,11,7)
      screen.fill()
      screen.level(15)
      screen.move(slice_played_x,slice_played_y-2)
      screen.text_center(tonumber(slice_played_ix) + 1)

      -- screen.stroke()
    end

    --show left transport slice
    if (transport_src_left_x) then
      screen.move(transport_src_left_x-3,transport_src_left_y)
      screen.line_rel(2,0)
      screen.move(transport_src_left_x,transport_src_left_y-2)
      screen.text_center(tonumber(transport_src_left_ix)+1)
      -- screen.stroke()
    end
    --show right transport slice
    if (transport_src_right_x) then
      screen.move(transport_src_right_x,transport_src_right_y)
      screen.line_rel(2,0)
      screen.move(transport_src_right_x,transport_src_right_y-2)
      screen.text_center(tonumber(transport_src_right_ix)+1)

      -- screen.stroke()
    end
    -- screen.move(cursor_x-4,cursor_y-1)
    screen.rect(cursor_x-2,cursor_y-2,4,4)
    -- screen.circle(cursor_x,cursor_y,5)
    -- screen.line(transport_src_right_x,transport_src_right_y,2)
    -- screen.stroke()
  elseif mode == "start" then
    screen.move(composition_left,8)
    screen.text("k2 to select folder/file...")
    -- screen.move(composition_left,16)
    -- screen.text("k1+k3 to record live...")
  elseif mode == "loading audio" then
    -- print("loading audio...")
    screen.move(composition_left,composition_top-6)
    screen.text("loading audio...")
  elseif mode == "audio composed" then
    print("show comp")
    if waveforms["composed"].waveform_samples then
      screen.move(composition_left,composition_top-6)
      -- screen.text("k1+k2 to transport audio...")
      screen.text("k3 to analyze audio...")
      waveforms["composed"]:redraw(composed_sig_pos)
    end
  elseif mode == "recording" then
    print("recording in progress...")
    screen.move(composition_left,composition_top-6)
    screen.text("recording in progress...")
    if waveforms["composed"].waveform_samples then
      waveforms["composed"]:redraw(composed_sig_pos)
    end
  elseif mode == "analysing" then
    screen.move(composition_left,composition_top-6)
    if slices_analyzed then
      screen.text("progress: "..slices_analyzed.."/"..total_slices)
    else
      screen.text("analysis in progress...")
    end
  end
  -- screen.peek(0, 0, 127, 64)
  screen.stroke()
  screen.update()
  screen_dirty = false
end

function cleanup ()
  -- print("cleanup",redrawtimer)
  waveform_render_queue=nil
  waveforms=nil
  if redrawtimer then metro.free(redrawtimer) end
  eglut:cleanup()
  gridcontrol:cleanup()
end