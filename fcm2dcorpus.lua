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

fileselect = include("lib/fileselect")



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

local mode = "start"
-- local composition_loaded = false
-- local analysis_in_progress = false
-- local points_data_generated=false
-- local show_composition = false

local alt_key=false
local menu_active = false
local points_data=nil
local cursor_x = 64
local cursor_y = 32
local highlight_x = nil
local highlight_y = nil

local scale = 30
local composition_top = 16
local composition_bottom = 62
local composition_left = 10
local composition_right = 125

local slices_analyzed
local total_slices

local audio_path = nil
local composition_length = nil
local analysis_start = nil
local analysis_end = nil
waveform_samples = nil
local max_analysis_length = 60 * 3

--------------------------
-- osc functions
--------------------------
local script_osc_event = osc.event

function osc.event(path,args,from)
  if script_osc_event then script_osc_event(path,args,from) end
  
  if path == "/lua_fcm2dcorpus/sc_inited" then
    print("fcm 2d corpus sc inited message received")
  elseif path == "/lua_fcm2dcorpus/compose_written" then
    if mode ~= "audio composed" then
      local path = args[1]
      print("audio composed", path)
      mode = "analysing composition"
      clock.run(load_compose,path)
    end
  elseif path == "/lua_fcm2dcorpus/composelive_written" then
    if mode ~= "audio composed" then
      local path = args[1]
      print("live audio composed", path)
      mode = "analysing composition"
      clock.run(load_compose,path)
    end
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
    -- tab.print(args)
    local slice_id = tostring(args[1])
    highlight_x = points_data[slice_id][1]
    highlight_y = points_data[slice_id][2]
    highlight_x = composition_left + math.ceil(highlight_x*(132-composition_left))
    highlight_y = composition_top + math.ceil(highlight_y*(64-composition_top))
    -- print(highlight_x,highlight_y)
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

function set_audio_path(path)
  print("set_2dcorpus",path)
  if path ~= "cancel" then
    mode = "loading audio"
    points_data=nil
    cursor_x = 64
    cursor_y = 32
    highlight_x = nil
    highlight_y = nil
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
  os.execute("mkdir -p " .. audio_path .. "/2d_audio")
end

function num_files_in_folder(path)
  local files = util.scandir (path)
  return #files
end

function init()
  create_audio_folder()
  params:add_control("cursor_x", "cursor x",controlspec.new(0,1,'lin',0.01,0.5,'',0.01))
  params:set_action("cursor_x", function(x) 
    cursor_x = util.clamp(x*127,composition_left,127)
    play_slice()
  end)
  params:add_control("cursor_y", "cursor y",controlspec.new(0,1,'lin',0.01,0.5,'',0.01))
  params:set_action("cursor_y", function(x) 
    cursor_y = util.clamp(x*64,composition_top,64)
    play_slice()
  end)
  params:add_trigger("select_folder_file", "select folder/file" )
  params:set_action("select_folder_file", function(x) fileselect.enter(_path.audio, set_audio_path) end)

  print("init>>>")
  if installer:ready() then
    print("fcm2d corpus ready")
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
  if k==2 then
    -- fileselect.enter('/home/we/dust/audio', set_audio_path)   
    params:set("select_folder_file",1)
  elseif k==3 and z==1 then
    if alt_key == true then
      mode = "recording"
      screen_dirty = true
      print("record live")
      local duration = 20
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/record_live",{duration})
    elseif mode == "audio composed" then
      mode = "analysing"
      screen_dirty = true
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/analyze_2dcorpus",{})
    elseif mode == "points generated" then
      local audio_path = _path.audio..norns.state.name.."/2d_audio"
      local file_num = num_files_in_folder(audio_path)+1
      local path = audio_path .. "/" .. tostring(file_num).."_src.wav"
      print("write src")
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/write_src",{path})
      local path = audio_path .. "/" .. tostring(file_num).."_normed.wav"
      print("write normed")
      osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/write_normed",{path})
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
      
    elseif n==3 then
      params:set("cursor_y",params:get("cursor_y")+(d/64))
    end
  end
  screen_dirty = true
end

function play_slice()
  local x = math.ceil(util.linlin(composition_left,128,1,128,cursor_x))
  local y = math.ceil(util.linlin(composition_top,64,1,64,cursor_y))
  osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/play_slice",{x/127,y/64})
end

------------------------------

function load_compose(path)
  clock.sleep(1)
  if path ~= "" then
    local ch, len = audio.file_info(path)
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
  analysis_start = 0
  analysis_end = math.min(max_analysis_length,composition_length)
  softcut.render_buffer(1, 1, composition_length, 128)
  print("analysis_end",analysis_end)
  softcut.loop_start(1, 0)
  softcut.loop_end(1, analysis_end)
  print("init reel > length: "..composition_length)
end

function on_waveform_render(ch, start, i, s)
  waveform_samples = s
  print("on_waveform_render",#waveform_samples, waveform_samples[1])
  mode = "audio composed"
  
  screen_dirty = true
end

function display_waveform()
  local x_pos = 0
  print("#waveform_samples",#waveform_samples)
  
  screen.level(15)
  screen.move(util.linlin(0,128,composition_left,composition_right,analysis_start), composition_top)
  screen.line_rel(0, composition_bottom-composition_top)
  screen.stroke()
  screen.move(util.linlin(0,128,composition_left,composition_right,(analysis_end/composition_length)*128), composition_top)
  screen.line_rel(0, composition_bottom-composition_top)
  screen.stroke()
  screen.level(2)
  screen.aa(1)
  screen.move(composition_left-2,composition_top-2)
  screen.rect(composition_left-2,composition_top-2,composition_right-composition_left+4,composition_bottom-composition_top+4)
  screen.stroke()
  screen.aa(0)
  screen.level(5)
  local center = composition_bottom-((composition_bottom-composition_top)/2)
  for i,s in ipairs(waveform_samples) do
    -- local height = util.round(math.abs(s) * (scale))
    local height = util.round(math.abs(s) * ((composition_top-composition_bottom)))
    -- local height = util.round(math.abs(s) * 30)
    screen.move(util.linlin(0,128,composition_left,composition_right,x_pos), center - (height/2))
    screen.line_rel(0, height)
    screen.stroke()
    x_pos = x_pos + 1
  end


end

-------------------------------
function redraw()
  if screen_dirty == true then
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
        --set cursor
        screen.move(cursor_x+4,cursor_y)
        screen.circle(cursor_x,cursor_y,5)

        for k,v in pairs(points_data) do 
          -- tab.print(k,v) 
          local x = composition_left + math.ceil(v[1]*(132-composition_left))
          local y = composition_top + math.ceil(v[2]*(64-composition_top))
          screen.move(x,y)
          screen.pixel(x,y)
          if (highlight_x and highlight_y) then
            screen.move(highlight_x+2,highlight_y)
            screen.circle(highlight_x,highlight_y,3)
          end
        end
      else 
        print("no points data")
      end
      screen.fill()
      screen.stroke()

    elseif mode == "start" then
      screen.move(10,10)
      screen.text("k2 to select folder/file...")
      screen.move(10,20)
      screen.text("k1+k3 to record live...")
    elseif mode == "loading audio" then
      print("loading audio...")
      screen.move(10,10)
      screen.text("loading audio...")
    elseif mode == "audio composed" then
      print("show comp")
      if waveform_samples then
        screen.move(10,10)
        screen.text("k3 to run analysis...")
        display_waveform()
      end
    elseif mode == "recording" then
      print("recording in progress...")
      screen.move(10,10)
      screen.text("recording in progress...")
    elseif mode == "analysing" then
      print("analysis in progress...")
      screen.move(10,10)
      if slices_analyzed then
        screen.text("progress: "..slices_analyzed.."/"..total_slices)
      else
        screen.text("analysis in progress...")
      end
    end
    screen.peek(0, 0, 128, 64)
    screen.update()
    screen_dirty = false
  end
end
