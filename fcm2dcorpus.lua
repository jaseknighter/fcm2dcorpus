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

CLOCK_RATE=15
if not string.find(package.cpath,"/home/we/dust/code/graintopia/lib/") then
  package.cpath=package.cpath..";/home/we/dust/code/graintopia/lib/?.so"
end

json = require "fcm2dcorpus/lib/json/json"

local points_data_generated=false
local points_data=nil
local cursor_x = 64
local cursor_y = 32
local highlight_x = nil
local highlight_y = nil

--------------------------
-- osc functions
--------------------------
local script_osc_event = osc.event

function osc.event(path,args,from)
  if script_osc_event then script_osc_event(path,args,from) end
  
  if path == "/lua_fcm2dcorpus/sc_inited" then
    print("fcm 2d corpus sc inited message received")
  elseif path == "/lua_fcm2dcorpus/analysis_done" then
    print("sc analysis done :PPPP")
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
    highlight_x = math.ceil(highlight_x*132)
    highlight_y = math.ceil(highlight_y*64)
    -- print(highlight_x,highlight_y)
  end
end

function load_json()
  clock.sleep(1)
  local data_file=(io.open('/tmp/temp_dataset.json', "r"))
  points_data=data_file:read("*all")
  points_data=json.decode(points_data)
  points_data=points_data["data"]
  data_file:close()
  points_data_generated=true
  screen_dirty = true
end

function init()
  if installer:ready() then
    osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/init",{data_path})
  end
  screen.aa(1)
  redrawtimer = metro.init(function() 
    redraw()
  end, 1/15, -1)
  redrawtimer:start()
  screen_dirty = true
end

function key(k,z)
  if not installer:ready() then
    installer:key(k,z)
    do return end
  end

  if k==2 then

  end
end

function enc(n,d)
  if not installer:ready() then
    do return end
  end
  if points_data_generated == true and points_data then

    if n==1 then

    elseif n==2 then
      cursor_x = util.clamp(cursor_x + d,1,132)
    elseif n==3 then
      cursor_y = util.clamp(cursor_y + d,1,64)
    end
    play_slice(cursor_x/132,cursor_y/64)
  end
  screen_dirty = true
end

function play_slice(x,y)
  osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/play_slice",{x,y})
end

function redraw()
  if screen_dirty == true then
    if not installer:ready() then
      installer:redraw()
      do return end
    end
  
    screen.clear()

    --set cursor
    screen.move(cursor_x+4,cursor_y)
    screen.circle(cursor_x,cursor_y,5)
    
    -- set data points
    if points_data_generated == true then
      if points_data then
        for k,v in pairs(points_data) do 
          -- tab.print(k,v) 
          local x = math.ceil(v[1]*132)
          local y = math.ceil(v[2]*64)
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

    else
      print("loading")
      screen.move(10,40)
      screen.text("loading ")

    end
    screen.peek(0, 0, 128, 64)
    screen.update()
    screen_dirty = false
  end
end
