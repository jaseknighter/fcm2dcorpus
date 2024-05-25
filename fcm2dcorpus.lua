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

fileselect=include("lib/fileselect")

eglut=include("lib/eglut")
waveform=include("lib/waveform")

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
local selecting_file = false
points_data=nil
local cursor_x = 64
local cursor_y = 32
local current_x = nil
local current_y = nil
local previous_x = nil
local previous_y = nil

local scale = 30
local composition_top = 16
local composition_bottom = 64-16
local composition_left = 16
local composition_right = 127-16

local slices_analyzed
local total_slices
local transporting_audio = false
local transport_gate = 1
local enc_debouncing=false


waveforms = {}
waveform_names = {"composed","transported"}
waveform_sig_positions = {}

local max_analysis_length = 60 * 3
local gslice_generated=false

--------------------------
-- osc functions
--------------------------
local script_osc_event = osc.event

function on_audio_composed(path)
  if mode ~= "audio composed" and mode ~= "analysing" then
    clock.sleep(0.5)
    mode = "analysing composition"
    local waveform_name=waveform_names[params:get("show_waveform")]
    if waveform_name=="composed" then
      waveforms["composed"].load(path,max_analysis_length,true)
    else
      waveforms["composed"].load(path,max_analysis_length,true)
    end
    if params:get("auto_analyze")==2 then
      mode = "analysing"
      screen_dirty = true
      local slice_threshold = params:get('slice_threshold')
      local min_slice_length = params:get('min_slice_length')
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/analyze_2dcorpus",{slice_threshold,min_slice_length})    else
      mode = "audio composed"
    end
  end
  -- end
end

function on_transportslice_composed(path)
  if mode == "points generated" then
    clock.sleep(0.01)
    local waveform_name=waveform_names[params:get("show_waveform")]
    if waveform_name=="transported" then
      waveforms["transported"].load(path,max_analysis_length,true)
    else
      waveforms["transported"].load(path,max_analysis_length,false)
    end
    print("transport slice waveform loaded")
  end
end

function on_livebuffer_written(path)
  clock.sleep(0.01)
  local selected=params:get("show_waveform")/2
  local gran_live_selected = selected>1 and selected/2 ~= math.floor(selected/2)
  if gran_live_selected==true then
    waveforms[math.floor(selected).."gran-live"].load(path,max_analysis_length,true)
  -- else
  --   waveforms["granulated"].load(path,max_analysis_length,false)
  end
end

function on_eglut_file_loaded(voice,file)
  print("on_eglut_file_loaded",file)
  if mode~="points generated" then
    mode="granulated"
  end
  local show_waveform=params:get("show_waveform")
  if show_waveform>2 then
    local sample_mode = params:get(voice.."sample_mode")
    local show_recorded_waveform = sample_mode == 3 and waveform_names[show_waveform] == voice.."gran-rec" 
    if show_recorded_waveform == true then
      print(voice,"show gran-rec waveform")
      waveforms[voice.."gran-rec"].load(file,max_analysis_length,true)
    end
  else
    print(voice,"save gran-rec waveform")
    waveforms[voice.."gran-rec"].load(file,max_analysis_length,false)
  end
end

function set_eglut_sample(file,samplenum,scene)
  print("set_eglut_sample",file,samplenum,scene)
  params:set(samplenum.."sample",file,true)
  clock.sleep(0.1)
  params:set(samplenum.."scene",3-scene)
  params:set(samplenum.."scene",scene)
end

function osc.event(path,args,from)
  if script_osc_event then script_osc_event(path,args,from) end
  
if path == "/lua_eglut/grain_sig_pos" then

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
    print("dumped to json")
    clock.run(load_json)
  elseif path == "/lua_fcm2dcorpus/slice_played" then
    local current_slice_id = tostring(args[1])
    local previous_slice_id = tostring(args[2])
    current_x = points_data[current_slice_id][1]
    current_y = points_data[current_slice_id][2]
    current_x = composition_left + math.ceil(current_x*(127-composition_left))
    current_y = composition_top + math.ceil(current_y*(64-composition_top))
    previous_x = points_data[previous_slice_id][1]
    previous_y = points_data[previous_slice_id][2]
    previous_x = composition_left + math.ceil(previous_x*(127-composition_left))
    previous_y = composition_top + math.ceil(previous_y*(64-composition_top))
  elseif path == "/lua_fcm2dcorpus/transportslice_composed" then
    path = args[1]
    print("transportslice_composed",path)
    clock.run(on_transportslice_composed,path)
    for i=1,eglut.num_voices do
      if params:get(i.."granulate_transport")==2 then
        clock.run(set_eglut_sample,path,i,1)
      end
    end
  elseif path == "/lua_fcm2dcorpus/livebuffer_written" then
    local path = args[1]
    clock.run(on_livebuffer_written,path)
  elseif path == "/lua_fcm2dcorpus/transport_sig_pos" then
    transport_sig_pos = args[1]
    -- print("transport_sig_pos",transport_sig_pos)
    if mode == "points generated" and selecting_file == false and norns.menu.status() == false then
      screen_dirty = true
    end
  end
end

function load_json()
  clock.sleep(2)
  local data_file=(io.open('/tmp/temp_dataset.json', "r"))
  points_data=data_file:read("*all")
  points_data=json.decode(points_data)
  points_data=points_data["data"]
  data_file:close()
  mode = "points generated" 
  screen_dirty = true
end

-- todo: rename to set_composed_audio_path
function set_audio_path(path)
  gslice_generated=false
  print("set_2dcorpus",path)
  selecting_file = false
  if path ~= "cancel" then
    mode = "loading audio"
    points_data=nil
    cursor_x = 64
    cursor_y = 32
    current_x = nil
    current_y = nil
    previous_x = nil
    previous_y = nil
    audio_path = path
    path_type = string.find(audio_path, '/', -1) == #audio_path and "folder" or "file"
    print("path_type",path_type)
    os.execute("rm '/tmp/temp_dataset.json'")    
    local max_sample_length = params:get('max_sample_length')
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_2dcorpus",{audio_path,path_type,max_sample_length})
    waveforms["composed"]:set_samples(nil)
    params:set("selected_sample",1)
    screen_dirty = true
  else
    screen_dirty=true
  end
end

function create_audio_folder()
  local audio_path = _path.audio..norns.state.name
  os.execute("mkdir -p " .. audio_path)
  os.execute("mkdir -p " .. audio_path .. "/temp")
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
    print("show_waveform",x) 
    if x>2 then
      print("write_live_stream_enabled",1)
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/write_live_stream_enabled",{1})  
    else
      -- print("write_live_stream_enabled",0)
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

  --slice params
  params:add_group("slice",7)
  params:add_trigger("select_folder_file", "select folder/file" )
  params:set_action("select_folder_file", function(x) selecting_file = true fileselect.enter(_path.audio, set_audio_path) end)
  params:add_trigger("record_live", "record live")
  params:set_action("record_live", function() 
    mode = "recording"
    screen_dirty = true
    print("record live")
    local duration = 10
    print("start record live")
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/record_live",{duration})
  end)
  params:add_option("auto_analyze","auto analyze",{"off","on"},2)
  params:add_control("max_sample_length","max sample length",controlspec.new(1,5,'lin',0.1,1.5,"min"))
  params:add_control("slice_threshold","slice threshold",controlspec.new(0,1,'lin',0.1,0.5))
  params:add_control("min_slice_length","min slice length",controlspec.new(0,100,'lin',0.1,2,"",0.001))
  params:add_control("slice_volume","slice volume",controlspec.new(0,1,'lin',0.1,1))

  --transport params
  params:add_group("transport",5+eglut.num_voices)
  params:add_control("transport_volume","transport volume",controlspec.new(0,1,'lin',0.1,1))
  params:set_action("transport_volume", function(vol)         
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_volume",{vol}) 
  end)

  params:add_control("transport_rate","transport rate",controlspec.new(0.01,10,'lin',0.01,1,"",1/10000))
  params:set_action("transport_rate", function()         
    local transport_rate = params:get('transport_rate')
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_rate",{transport_rate}) 
    -- local function callback_func()
    -- end
    -- clock.run(enc_debouncer,callback_func)
  end)


  params:add_control("transport_trig_rate","transport trig rate",controlspec.new(0.1,120,'lin',0.1,4,"/beat",1/1200))
  params:set_action("transport_trig_rate", function()         
    local function callback_func()
      local trig_rate = params:get('transport_trig_rate')/(clock.get_beat_sec())
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_trig_rate",{trig_rate}) 
    end
    clock.run(enc_debouncer,callback_func)
  end)
  params:add_control("transport_reset_pos","transport reset pos",controlspec.new(0,1,'lin',0,0))
  params:set_action("transport_reset_pos", function()         
    local function callback_func()
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_reset_pos",{params:get('transport_reset_pos')}) 
    end
    clock.run(enc_debouncer,callback_func)
  end)

  params:add_control("transport_stretch","transport stretch",controlspec.new(0,5,'lin',0.01,0,'',1/1000))
  params:set_action("transport_stretch", function()  
    local function callback_func()
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_stretch",{params:get('transport_stretch')}) 
    end
    clock.run(enc_debouncer,callback_func)
  end)
  for i=1,eglut.num_voices do
    local default=i==1 and 2 or 1
    params:add_option(i.."granulate_transport","granulate->"..i,{"off","on"},default)
  end

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
  create_audio_folder()
  setup_waveforms()
  setup_params()
  params:set("transport_volume",0.2)
  
  eglut:init(on_eglut_file_loaded)
  eglut:setup_params()
  print("eglut inited and params setup")
  -- params:set("1play1",2)

  screen.aa(0)
  softcut.event_render(on_waveform_render)

  redrawtimer = metro.init(function() 
    if norns.menu.status() == true or fileselect.done~=false then
      redraw()
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
  osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/init_completed")
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
    elseif mode == "points generated" and current_x then
      local x = math.ceil(util.linlin(composition_left,127,1,127,cursor_x))
      local y = math.ceil(util.linlin(composition_top,64,1,64,cursor_y))    
      transporting_audio = true
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/transport_slices",{x/127,y/64})
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
        -- engine.volume(1,(y-composition_top)/(64-composition_top))
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
  osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/play_slice",{x/127,y/64,params:get("slice_volume")})
end

function on_waveform_render(ch, start, i, s)
  local waveform_name=waveform_names[params:get("show_waveform")]
  if waveform_name == "composed" then
    waveforms["composed"]:set_samples(s)
    print("on waveform render composed: mode, s",mode,#s)
  elseif waveform_name == "transported" then
    waveforms["transported"]:set_samples(s)
    print("on waveform render transported: mode, s",mode,#s)
  else
    for i=1,eglut.num_voices do
      if waveform_name == i.."gran-live" then
        waveforms[i.."gran-live"]:set_samples(s)
      elseif waveform_name == i.."gran-rec" then
        waveforms[i.."gran-rec"]:set_samples(s)
      end
    end
  end
  screen_dirty = true
end


-------------------------------
function redraw()
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
    waveforms[show_waveform_name]:redraw(waveform_sig_positions[show_waveform_name])
  end
  

  -- set data points
  if mode == "points generated" then
    if points_data then
      if slices_analyzed then
        slices_analyzed = nil
        total_slices = nil
      end

      for k,v in pairs(points_data) do 
        -- tab.print(k,v) 
        local x = composition_left + math.ceil(v[1]*(127-composition_left))
        local y = composition_top + math.ceil(v[2]*(64-composition_top))
        screen.move(x,y-2)
        screen.line_rel(0,4)
        screen.stroke()
      end
    else 
      print("no points data")
    end
    --set cursor
    if (current_x and current_y) then
      screen.move(current_x+2,current_y)
      screen.circle(current_x,current_y,3)
      screen.fill()
    end
    screen.stroke()
    if (previous_x and previous_y) then
      screen.move(previous_x+2,previous_y)
      screen.circle(previous_x,previous_y,3)
    end
    screen.stroke()
    screen.move(cursor_x+4,cursor_y)
    screen.circle(cursor_x,cursor_y,5)
    -- screen.line(previous_x,previous_y,2)
    screen.stroke()
  elseif mode == "start" then
    screen.move(composition_left,8)
    screen.text("k2 to select folder/file...")
    screen.move(composition_left,16)
    screen.text("k1+k3 to record live...")
  elseif mode == "loading audio" then
    print("loading audio...")
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
    -- print("analysis in progress...")
    -- if waveforms["composed"].waveform_samples then
    --   waveforms["composed"]:redraw(composed_sig_pos)
    -- end
    screen.move(composition_left,composition_top-6)
    if slices_analyzed then
      screen.text("progress: "..slices_analyzed.."/"..total_slices)
    else
      screen.text("analysis in progress...")
    end
  end
  screen.peek(0, 0, 127, 64)
  screen.update()
  screen_dirty = false
end

function cleanup ()
  print("cleanup",redrawtimer)
  -- if redrawtimer then metro.free(redrawtimer) end
  -- eglut:cleanup()
end