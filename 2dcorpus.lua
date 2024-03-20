json = require "fcm_2dcorpus/lib/json/json"

-- engine.name=installer:ready() and 'Graintopia' or nil
engine.name='FCM2dCorpus'

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
  
  -- script_osc_event(path,args,from)
  
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
    print(highlight_x,highlight_y)
    
    -- print(slice_id,highlight_x,highlight_y)
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

-- FluCoMa Example: 2d corpus explorer
--
-- E1 ????
-- E2 x
-- E3 y

function init()
  screen.aa(1)
    print("rd")
  osc.send( { "localhost", 57120 }, "/sc_fcm2dcorpus/init",{data_path})
  redrawtimer = metro.init(function() 
    redraw()
  end, 1/15, -1)
  redrawtimer:start()
  print(">>>>>>>>>")
  screen_dirty = true
end

function enc(n,d)
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
    -- screen.move(10,30)
    -- screen.text("rate: ")
    -- screen.move(118,30)
    -- screen.text_right(string.format("%.2f",rate))
    -- screen.move(10,40)
    -- screen.text("loop_start: ")
    -- screen.move(118,40)
    -- screen.text_right(string.format("%.2f",loop_start))
    -- screen.move(10,50)
    -- screen.text("loop_end: ")
    -- screen.move(118,50)
    -- screen.text_right(string.format("%.2f",loop_end))
    screen_dirty = false
  end
end


-- function print_info(file)
--   if util.file_exists(file) == true then
--     local ch, samples, samplerate = audio.file_info(file)
--     local duration = samples/samplerate
--     print("loading file: "..file)
--     print("  channels:\t"..ch)
--     print("  samples:\t"..samples)
--     print("  sample rate:\t"..samplerate.."hz")
--     print("  duration:\t"..duration.." sec")
--   else print "read_wav(): file not found" end
-- end
