-- from @infinitedigits graintopia

local Waveform={}

function Waveform:new(args)
  local wf=setmetatable({},{__index=Waveform})
  local args=args==nil and {} or args
  for k,v in pairs(args) do
    wf[k]=v
  end
  wf:init()
  return wf
end

function Waveform:init()
  -- self.is_rendering=false
  -- self.rendering_name=nil
  -- self.renders={}
end

-- src = _path.audio..norns.state.name.."/temp/src.wav"
function Waveform.load(path,max_len)
  if path ~= "" then
    local ch, samples = audio.file_info(path)
    print(path)
    if ch > 0 and samples > 0 then
      softcut.buffer_clear()
      clock.run(function()
        softcut.buffer_read_mono(path, 0, 1, -1, 1, 1, 0, 1)
        local len = (samples / 48000)
        local waveform_start = 1
        local waveform_end = max_len and math.min(max_len,len) or len
        print(string.format("[waveform] loading %s",path))
        softcut.render_buffer(1, waveform_start, waveform_end, 127)
        -- softcut.loop_start(1, waveform_start)
        -- softcut.loop_end(1, waveform_end)
        print("waveform loaded: "..path.." is "..len.."s / "..waveform_end.."s")
  
        -- softcut.buffer_read_mono(path,0,1,-1,1,1)
        -- print(string.format("[waveform] rendering %2.1f sec of %s",length,fname))
        -- softcut.render_buffer(1,1,path,128)
      end)
    end
  else
    print("not a sound file")
  end

end

function Waveform:set_samples(samples)
  self.waveform_samples = samples
end

function Waveform:display_sigs_pos(sigs_pos)
  --show signal position(s)
  if #sigs_pos > 0 then
    local center = self.composition_bottom-((self.composition_bottom-self.composition_top)/2)
    for i=1,#sigs_pos do
      local sig_pos = sigs_pos[i]
      local height = util.round(self.composition_top-self.composition_bottom+6)
      screen.move(util.linlin(0,127,self.composition_left,self.composition_right,math.floor(sig_pos*127)), center - (height/2))
      screen.line_rel(0, height)
      screen.stroke()
    end
  end
end

function Waveform:display_slices(slices)
  for i=1, #slices, 2 do
    local slice_pos1 = util.round(util.linlin(0,127, self.composition_left,self.composition_right, slices[i]/slices[#slices]*127))
    local slice_pos2 = util.round(util.linlin(0,127, self.composition_left,self.composition_right, slices[i+1]/slices[#slices]*127))
    local slice_num = math.floor((i+1)/2)
    local selected_slice = params:get(params:get("selected_sample").."selected_gslice"..params:get(params:get("selected_sample").."scene"))
    local text_level = slice_num == selected_slice and 15 or 0
    screen.level(15)
    screen.move(slice_pos1,self.composition_top-2)
    screen.line_rel(0, 4)
    screen.move(slice_pos2,self.composition_top-2)
    screen.line_rel(0, 4)      
    screen.stroke()
    screen.level(text_level)
    screen.move(slice_pos1-2,self.composition_top-3)
    screen.text(slice_num)
    screen.stroke()
  end
end

function Waveform:display_waveform()
  local x_pos = 0
  
  screen.level(1)
  -- screen.aa(1)
  screen.move(self.composition_left-2,self.composition_top-2)
  screen.rect(self.composition_left-2,self.composition_top-2,self.composition_right-self.composition_left+4,self.composition_bottom-self.composition_top+4)
  screen.stroke()
  -- screen.aa(0)
  screen.level(3)
  local center = self.composition_bottom-((self.composition_bottom-self.composition_top)/2)
  for i,s in ipairs(self.waveform_samples) do
    local height = util.round(math.abs(s) * ((self.composition_top-self.composition_bottom)))
    screen.move(util.linlin(0,127,self.composition_left,self.composition_right,x_pos), center - (height/2))
    screen.line_rel(0, height)
    screen.stroke()
    x_pos = x_pos + 1
  end
  screen.level(5)
end

function Waveform:redraw(sigs_pos, slices)
  if self.active==false then
    do return end
  end

  self:display_waveform()

  --show slice positions
  if slices then
    self:display_slices(slices)
  end
  screen.level(15)

  --show signal(s) positions
  if sigs_pos then
    self:display_sigs_pos(sigs_pos)
  end

end

return Waveform
