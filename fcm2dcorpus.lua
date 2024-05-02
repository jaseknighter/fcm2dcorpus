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
-- FluCoMa installer code from @infinitedigits (schoolz) graintopia

-- check for requirements
installer_=include("lib/scinstaller/scinstaller")
installer=installer_:new{install_all="true",folder="FluidCorpusManipulation",requirements={"FluidCorpusManipulation"},zip="https://github.com/jaseknighter/flucoma-sc/releases/download/1.0.6-RaspberryPi/FluCoMa-SC-RaspberryPi.zip"}
engine.name=installer:ready() and 'FCM2dCorpus' or nil

fileselect=include("lib/fileselect")

eglut = include("lib/eglut")


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

local alt_key=false
local selecting_file = false
local menu_active = false
local points_data=nil
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

gslices = {}

local audio_path = nil
local composition_length = nil
local waveform_start = nil
local waveform_end = nil
local waveform_samples = nil
local max_analysis_length = 60 * 3
local slice_generated=false
local pos_sigs = {}
--------------------------
-- osc functions
--------------------------
local script_osc_event = osc.event

function on_audio_composed(path)
  if mode ~= "audio composed" and mode ~= "analysing" then
    clock.sleep(0.5)
    mode = "analysing composition"
    clock.run(load_waveform,path)
    
    
    -- if mode == "analysing composition" then
    if params:get("auto_analyze")==2 then
      mode = "analysing"
      screen_dirty = true
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/analyze_2dcorpus",{})
    else
      mode = "audio composed"
    end
  end
  -- end
end

function osc.event(path,args,from)
  if script_osc_event then script_osc_event(path,args,from) end
  
  if path == "/lua_fcm2dcorpus/sc_inited" then
    print("fcm 2d corpus sc inited message received")
  elseif path == "/lua_fcm2dcorpus/compose_written" then
    -- if mode ~= "audio composed" then
      local path = args[1]
      -- print("audio composed", path)
      -- clock.run(load_waveform,path)
    -- end
    clock.run(on_audio_composed,path)
  elseif path == "/lua_fcm2dcorpus/composelive_written" then
    -- if mode ~= "audio composed" then
      local path = args[1]
      -- print("audio live composed", path)
      -- clock.run(load_waveform,path)
    -- end
    clock.run(on_audio_composed,path)
  elseif path == "/lua_fcm2dcorpus/analyze_written" then
    print("analysis written", path)
  elseif path == "/lua_fcm2dcorpus/analysis_progress" then
    slices_analyzed = args [1]
    total_slices = args[2]
    screen_dirty = true
  elseif path == "/lua_fcm2dcorpus/analysis_dumped" then
    -- osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/get_dump")
    print("dumped to json")
    clock.run(load_json)
  elseif path == "/lua_fcm2dcorpus/dump_points" then
    print("dump points")
    tab.print(args)
  elseif path == "/lua_fcm2dcorpus/slice_played" then
    local current_slice_id = tostring(args[1])
    local previous_slice_id = tostring(args[2])
    -- tab.print(args)
    current_x = points_data[current_slice_id][1]
    current_y = points_data[current_slice_id][2]
    current_x = composition_left + math.ceil(current_x*(127-composition_left))
    current_y = composition_top + math.ceil(current_y*(64-composition_top))
    previous_x = points_data[previous_slice_id][1]
    previous_y = points_data[previous_slice_id][2]
    previous_x = composition_left + math.ceil(previous_x*(127-composition_left))
    previous_y = composition_top + math.ceil(previous_y*(64-composition_top))
  elseif path == "/lua_fcm2dcorpus/gslicebuf_composed" then
    slice_generated=true
  elseif path == "/lua_fcm2dcorpus/gslicebuf_appended" then
    print("gslicebuf_appended")
    local file = args[1]
    local samplenum = 1
    local scene = 1
    clock.run(set_eglut_sample,file,samplenum,scene)
  elseif path == "/lua_fcm2dcorpus/set_gslices" then
    local gslices_str = args[1]
    gslices={}
    for slice in string.gmatch(gslices_str, '([^,]+)') do
      table.insert(gslices,slice) 
    end
    local selected_sample = params:get("selected_sample")
    local selected_scene = params:get(selected_sample.."scene")
    eglut:update_gslices(selected_sample,selected_scene)
  elseif path == "/lua_fcm2dcorpus/grain_pos_sig" then
    pos_sigs = args
    if mode == "points generated" and selecting_file == false and norns.menu.status() == false then
      screen_dirty = true
    end
  end
end

function set_eglut_sample(file,samplenum,scene)
  print("set_eglut_sample: get slices",file,samplenum,scene)
  osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/get_gslices")
  clock.sleep(0.5)
  params:set(samplenum.."sample"..(scene),file,true)
  clock.sleep(0.1)
  params:set(samplenum.."scene",3-scene)
  params:set(samplenum.."scene",scene)
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

function set_audio_path(path)
  slice_generated=false
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
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_2dcorpus",{audio_path,path_type})
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

function setup_params()
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
  params:add_control("slice_volume","slice volume",controlspec.new(0,1,'lin',0.1,1))
  params:add_control("transport_volume","transport volume",controlspec.new(0,1,'lin',0.1,1))
  params:set_action("transport_volume", function(vol)         
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_volume",{vol}) 
  end)
  params:add_control("transport_trig_rate","transport trig rate",controlspec.new(1,120,'lin',1,12,"/beat",1/120))
  params:set_action("transport_trig_rate", function(trig_rate)         
    trig_rate = trig_rate/(clock.get_beat_sec())
    -- trig_rate = trig_rate/(4*clock.get_beat_sec())
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_trig_rate",{trig_rate}) 
  end)
  params:add_control("transport_reset_pos","transport reset pos",controlspec.new(0,1,'lin',0,1))
  params:set_action("transport_reset_pos", function(pos)         
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_reset_pos",{pos}) 
  end)
  params:add_control("transport_stretch","transport stretch",controlspec.new(-5,5,'lin',0.01,0,'',1/1000))
  params:set_action("transport_stretch", function(stretch)         
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/set_transport_stretch",{stretch}) 
  end)
  
end



function init()
  print("init>>>")
  
  if installer:ready() then
    print("fcm2d corpus ready")
    create_audio_folder()
    setup_params()
    eglut:setup_params()
    -- osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/init")
  end
  screen.aa(0)

  redrawtimer = metro.init(function() 
    if norns.menu.status() == true and menu_active == false then
      menu_active = true
    elseif norns.menu.status() == false and menu_active == true then
      menu_active = false
      screen_dirty = true
    end
    redraw()
  end, 1/15, -1)
  redrawtimer:start()
  softcut.event_render(on_waveform_render)
  screen_dirty = true
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
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/transport_slice",{x/127,y/64})
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
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/analyze_2dcorpus",{})
    elseif mode == "points generated" then
      if slice_generated == true then
        print("append slice")
        osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/append_slice",{})
    end
      
      -- if last_slicebuf then
      --   tab.print(last_slicebuf)
      --   last_slicebuf = nil
      -- end

      -- code to write current audio to file
      -- local audio_path = _path.audio..norns.state.name.."/2d_audio"
      -- local file_num = num_files_in_folder(audio_path)+1
      -- local path = audio_path .. "/" .. tostring(file_num).."_src.wav"
      -- print("write src")
      -- osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/write_src",{path})
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
        local x = math.ceil(util.linlin(composition_left,127,0,127,cursor_x))
        local y = math.ceil(util.linlin(composition_top,64,0,64,cursor_y))      
        osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/transport_slice_x_y",{x/127,y/64})
      end
      
    elseif n==3 then
      params:set("cursor_y",params:get("cursor_y")+(d/64))
      local x = math.ceil(util.linlin(composition_left,127,0,127,cursor_x))
      local y = math.ceil(util.linlin(composition_top,64,0,64,cursor_y))      
      if alt_key == true then
        -- engine.volume(1,(y-composition_top)/(64-composition_top))
        if transporting_audio == true then 
          osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/transport_slice_x_y",{x/127,y/64})
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

------------------------------

function load_waveform(path)
  clock.sleep(0.01)
  if path ~= "" then
    local ch, len = audio.file_info(path)
    print(path)
    if ch > 0 and len > 0 then
      filename_reel = path
      softcut.buffer_clear()
      softcut.buffer_read_mono(path, 0, 1, -1, 1, 1, 0, 1)
      local l = (len / 48000)
      init_waveform(l)
      print("file loaded: "..path.." is "..l.."s")
    else
      print("not a sound file")
    end
    -- params:set("load_reel", "", true)
  end
end

function init_waveform(dur)
  -- active_splice = 1
  -- splice = {}
  -- splice[1] = {}
  -- splice[1].s = 1
  -- splice[1].e = 1 + dur
  -- splice[1].l = dur
  composition_length = dur
  waveform_start = 0
  waveform_end = math.min(max_analysis_length,composition_length)
  softcut.render_buffer(1, 1, composition_length, 127)
  print("waveform_end",waveform_end)
  softcut.loop_start(1, 0)
  softcut.loop_end(1, waveform_end)
  print("init reel > length: "..composition_length)
end

function on_waveform_render(ch, start, i, s)
  waveform_samples = s
  print("on_waveform_render",mode,#waveform_samples, waveform_samples[1])
  screen_dirty = true
end

function display_waveform(pos_sigs)
  local x_pos = 0
  
  -- screen.level(3)
  -- screen.move(util.linlin(0,127,composition_left,composition_right,waveform_start), composition_top)
  -- screen.line_rel(0, composition_bottom-composition_top)
  -- screen.stroke()
  -- screen.move(util.linlin(0,127,composition_left,composition_right,(waveform_end/composition_length)*127), composition_top)
  -- screen.line_rel(0, composition_bottom-composition_top)
  -- screen.stroke()
  screen.level(1)
  -- screen.aa(1)
  screen.move(composition_left-2,composition_top-2)
  screen.rect(composition_left-2,composition_top-2,composition_right-composition_left+4,composition_bottom-composition_top+4)
  screen.stroke()
  -- screen.aa(0)
  screen.level(3)
  local center = composition_bottom-((composition_bottom-composition_top)/2)
  for i,s in ipairs(waveform_samples) do
    local height = util.round(math.abs(s) * ((composition_top-composition_bottom)))
    screen.move(util.linlin(0,127,composition_left,composition_right,x_pos), center - (height/2))
    screen.line_rel(0, height)
    screen.stroke()
    x_pos = x_pos + 1
  end
  screen.level(5)
  if pos_sigs then
    --show signal position(s)
    for i=1,#pos_sigs do
      local pos_sig = pos_sigs[i]
      local height = util.round(composition_top-composition_bottom+6)
      screen.move(util.linlin(0,127,composition_left,composition_right,math.floor(pos_sig*127)), center - (height/2))
      screen.line_rel(0, height)
      screen.stroke()
    end
    --show slice positions
    if #gslices > 1 then
      for i=1, #gslices, 2 do
        local slice_pos1 = util.round(util.linlin(0,127, composition_left,composition_right, gslices[i]/gslices[#gslices]*127))
        local slice_pos2 = util.round(util.linlin(0,127, composition_left,composition_right, gslices[i+1]/gslices[#gslices]*127))
        local gslice_num = math.floor((i+1)/2)
        local selected_scene = params:get(params:get("selected_sample").."selected_gslice"..params:get(params:get("selected_sample").."scene"))
        local text_level = gslice_num== selected_scene and 15 or 0
        screen.level(15)
        screen.move(slice_pos1,composition_top-2)
        screen.line_rel(0, 4)
        screen.move(slice_pos2,composition_top-2)
        screen.line_rel(0, 4)      
        screen.stroke()
        screen.level(text_level)
        screen.move(slice_pos1-2,composition_top-3)
        screen.text(gslice_num)
        screen.stroke()
      end
    end
    screen.level(15)
  end



end

-------------------------------
function redraw()
  if screen_dirty == true then
    screen.level(15)
    if not installer:ready() then
      installer:redraw()
      do return end
    end
    screen.clear()

    
    -- set data points
    if mode == "points generated" then
      if points_data then
        if slices_analyzed then
          slices_analyzed = nil
          total_slices = nil
        end
        if waveform_samples then
          display_waveform(pos_sigs)          
        end
        for k,v in pairs(points_data) do 
          -- tab.print(k,v) 
          local x = composition_left + math.ceil(v[1]*(127-composition_left))
          local y = composition_top + math.ceil(v[2]*(64-composition_top))
          screen.move(x,y-1)
          screen.line_rel(0,2)
          -- screen.pixel(x,y)
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
      screen.stroke()
    elseif mode == "start" then
      screen.move(composition_left,composition_top-6)
      screen.text("k2 to select folder/file...")
      screen.move(composition_left,composition_top+6)
      screen.text("k1+k3 to record live...")
    elseif mode == "loading audio" then
      print("loading audio...")
      screen.move(composition_left,composition_top-6)
      screen.text("loading audio...")
      waveform_samples = nil
    elseif mode == "audio composed" then
      print("show comp")
      if waveform_samples then
        screen.move(composition_left,composition_top-6)
        -- screen.text("k1+k2 to transport audio...")
        screen.text("k3 to analyze audio...")
        display_waveform()
      end
    elseif mode == "recording" then
      print("recording in progress...")
      screen.move(composition_left,composition_top-6)
      screen.text("recording in progress...")
    elseif mode == "analysing" then
      print("analysis in progress...")
      if waveform_samples then
        display_waveform()
      end
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
end
