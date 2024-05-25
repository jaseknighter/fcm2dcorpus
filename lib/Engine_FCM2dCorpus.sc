// Engine_FCM2dCorpus

//thanks to infinite digits for using envelopes to loop buffers writeup 
//  https://infinitedigits.co/tinker/sampler/

// Inherit methods from CroneEngine
Engine_FCM2dCorpus : CroneEngine {
  
  // var s;
  var buses;
  var osc_funcs;
  var recorders;
  var players;
  var transportBuffers;
  var gslices;
  var max_slice_dur=2;
  var eglut;

	*new { arg context,doneCallback;
		^super.new(context,doneCallback);
	}


  alloc {
    var s=context.server;
    var compose,writebuf,append_gslice,remove_gslice,renumber_reduced_slice_array,analyze,dump_normed,play_slice,lua_sender,sc_sender;
    var composewritebufdone,composelivewritebufdone,analyzewritebufdone,transportwritebufdone,livewritebufdone;
    var indices;
    var gslicebuf;
    var gslicesbuf;
    var gslicesbuf_temp;
    var gslices;
    var tree,point,findices,current_slice,previous_slice;
    var src,srcSampleRate,normedBuf;
    var playing_slice=false;
    var audio_path="/home/we/dust/audio/fcm2dcorpus/";
    var transport_volume=1;
    var transport_rate=1;
    var transport_trig_rate=4;
    var transport_sig_pos=0;
    var transport_reset_pos=0;
    var transport_stretch=0;
    var transportRecorder;
    var transport_buffer_dummy;
    var transport_buffer_current=0;
    var transport_buffer_next=0;
    var buf_recording=0;
    var buf_writing=0;
    var x_loc;
    var server_sample_rate=48000;
    var live_buffer;
    var compose_buffer_recorder_ix=0;
    var transport_buffer_recorder_ix=0;

    ["memsize",s.options.memSize].postln;
    s.options.memSize=8192*4; 
    ["memsize post",s.options.memSize].postln;
    
    // s.sync;

    lua_sender=NetAddr.new("127.0.0.1",10111);   
    sc_sender=NetAddr.new("127.0.0.1",57120);   
    // lua_sender.sendMsg("/lua_fcm2dcorpus/sc_inited");

    buses=Dictionary.new();
    osc_funcs=Dictionary.new();
    recorders=Dictionary.new();
    players=Dictionary.new();
    "dictionaries created".postln;
    s.sync;

    transportBuffers = Array.fill(10,{
      Buffer.alloc(s,48000*60*3);
    });
    s.sync;
    "transport buffers allocated".postln;


    ////
    indices=Buffer.new(s);
    gslicebuf=Buffer.alloc(s,1);
    gslicesbuf=Buffer.alloc(s,1);
    gslicesbuf_temp=Buffer.alloc(s,1);
    gslices=Array.new();
    transport_buffer_dummy=Buffer.alloc(s,1);
    live_buffer = Buffer.alloc(s, s.sampleRate * 3);
    ////
    "initial buffers and arrays allocated".postln;
    s.sync;
    buses.put("busTransport",Bus.audio(s,1));
    s.sync;
    ////
    "initial busses allocated".postln;
    Routine({
      var transport_path;
      10.do{
        arg i;
        transport_path="/home/we/dust/audio/fcm2dcorpus/temp/transport" ++ i ++ ".wav";
        File.delete(transport_path);
      }
    }).play;



    composewritebufdone={
      arg path;
      src.query({ | msgType,bufnum,numFrames,numChannels,sampleRate |
        srcSampleRate=sampleRate;
        ["compose writebufdone",srcSampleRate].postln;
        lua_sender.sendMsg("/lua_fcm2dcorpus/compose_written",path);
      });
      buf_writing=0;
    };

    composelivewritebufdone={
      arg path;
      src.query({ | msgType,bufnum,numFrames,numChannels,sampleRate |
        srcSampleRate=sampleRate;
        ["compose live writebufdone",srcSampleRate].postln;
        lua_sender.sendMsg("/lua_fcm2dcorpus/composelive_written",path);
      });
      buf_writing=0;
    };

    analyzewritebufdone={
      arg path;
      "analyze writebufdone".postln;
      lua_sender.sendMsg("/lua_fcm2dcorpus/analyze_written");
      buf_writing=0;
    };
    
    transportwritebufdone={
      arg path;
      "transport writebufdone".postln;
      lua_sender.sendMsg("/lua_fcm2dcorpus/transportslice_composed",path);
      buf_writing=0;
    };

    livewritebufdone={
      arg path;
      // arg sample, path;
      lua_sender.sendMsg("/lua_fcm2dcorpus/livebuffer_written",path);
      buf_writing=0;
    };

    writebuf={
      arg buf,path,header_format,sample_format,numFrames,msg;
      // if ((buf_writing==0),{
      // ["starting writebuf",buf.bufnum,buf_writing,buf_recording].postln;
      
      if ((buf.sampleRate!=nil).and(buf_recording==0).and(buf_writing==0),{
        Routine({
          
          buf.normalize();
          s.sync;
          buf_writing=1;
          if (msg=="compose",{
            buf.write(path,header_format,sample_format,numFrames,completionMessage:{composewritebufdone.(path)});
          });
          if (msg=="composelive",{
            "write live recording".postln;
            buf.write(path,header_format,sample_format,numFrames,completionMessage:{composelivewritebufdone.(path)});
          });
          if (msg=="analyze",{
            buf.write(path,header_format,sample_format,numFrames,completionMessage:{analyzewritebufdone.(path)});
          });
          if (msg=="transport",{
            buf.write(path,header_format,sample_format,numFrames,completionMessage:{transportwritebufdone.(path)});
          });
          if (msg=="live",{
            buf.write(path,header_format,sample_format,numFrames,completionMessage:{livewritebufdone.(path)});
          });
        }).play;
      },{
        "buf writing already".postln;
      });
    };

    SynthDef("live_streamer", {
      arg in=0,out=0, 
          buf=0, rate=1, ptrdelay=0.2,dry_wet=1,
          write_live_stream_enabled=0;
      var sig=SoundIn.ar(in,2);
	    var ptr, gran, maxgraindur;
      var dry_sig=LinLin.kr(dry_wet,1,0,0,1);
      var wet_sig=LinLin.kr(dry_wet,0,1,0,1);
      dry_sig=min(1,dry_sig);
      wet_sig=max(0,wet_sig);
	    sig = SoundIn.ar(in);
	    // ptr = Phasor.ar(0, BufRateScale.ir(buf), 0, BufFrames.kr(buf));
	    // BufWr.ar(sig*wet_sig, buf, ptr);
      RecordBuf.ar(sig*wet_sig, buf, offset: 0.0, recLevel: 1.0, preLevel: 0.0, run: 1.0, loop: 1.0, trigger: 1.0, doneAction: 0);

      Out.ar(out,sig.dup*dry_sig);
      SendReply.kr(Impulse.kr(10)*write_live_stream_enabled,"/sc_fcm2dcorpus/write_live_streamer",BufFrames.kr(buf));
    }).add;

    SynthDef("live_recorder",{ 
      arg buf,rate=1,dur=10.0;
      // var dur=s.sampleRate * dur;
      var in=SoundIn.ar(0);
      RecordBuf.ar(in,buf,loop:0,doneAction:2);
      0.0 //quiet
    }).add;

    SynthDef("transport_bus_player",{ 
      arg in=0,out=0;
      var sig;
      sig = In.ar(in,1);
      out=Out.ar(out,sig);
      
     }).add;
              
    SynthDef("transport_synth",{ 
      arg out=0, transport_buffer, send_sig_pos=0,
          xloc=0.5,yloc=0.5,src,index=0,transport_rate=1,gate=1,
          transport_trig_rate=4,
          startsamp,stopsamp,startsamp_prev,stopsamp_prev,
          windowSize=256,hopSize=128,fftSize=256,tamp=1,
          reset_pos=0,stretch=0,
          record_buf=0;
      var phase,dursecs,dursecs_prev;
      var sig,loop_env;
      var tenv,trig_rate,phasor_trig,env_trig;
      var reset_posL,phaseL,sigL;
      var reset_phase,reset_phaseL;
      var transportSig=0;
      var transportGrains;
      var transport_startsamp;
      var transport_stopsamp;
      var recordbuf_offset;

      x_loc=xloc;
      // startsamp_prev
      stopsamp=stopsamp+(stretch*BufSampleRate.kr(src));
      stopsamp_prev=stopsamp_prev+(stretch*BufSampleRate.kr(src));
      dursecs=(stopsamp - startsamp) / BufSampleRate.kr(src);
      dursecs_prev=(stopsamp_prev - startsamp_prev) / BufSampleRate.kr(src);
      dursecs=LinLin.kr(x_loc,0,1,dursecs,dursecs_prev);

      trig_rate=(transport_trig_rate);
      // trig_rate=(transport_trig_rate*(1-record_buf));

      phasor_trig=Impulse.kr(trig_rate);
      
      // env_trig=Impulse.kr(trig_rate);
      // env=EnvGen.kr(Env([0,1,1,0],[0.05,dursecs-1,0.05]),gate:gate* env_trig);
      
      reset_pos=startsamp + (reset_pos*(stopsamp - startsamp));      
      reset_phase=Phasor.ar(phasor_trig,transport_rate,startsamp,stopsamp,reset_pos);
      phase=Phasor.ar(Impulse.kr(100),transport_rate,startsamp,stopsamp,reset_phase);
      sig=BufRd.ar(1,src,phase);

      reset_posL=startsamp_prev + (reset_pos*(stopsamp_prev - startsamp_prev));
      reset_phaseL=Phasor.ar(Impulse.kr(100),transport_rate,startsamp_prev,stopsamp_prev,reset_posL);
      phaseL=Phasor.ar(phasor_trig,transport_rate,startsamp_prev,stopsamp_prev,reset_phaseL);
      sigL=BufRd.ar(1,src,phaseL);

      transportSig=FluidAudioTransport.ar(sig,sigL,xloc,windowSize,hopSize,fftSize);
      
      
      // recordbuf_offset=LinLin.kr(xloc,0,1,phase,phaseL);
      recordbuf_offset=LinLin.kr(phase,startsamp,stopsamp,0,stopsamp-startsamp);
      RecordBuf.ar(transportSig,transport_buffer,offset: recordbuf_offset,run: record_buf,loop:1,trigger: Impulse.kr(30)*(record_buf));
      transport_sig_pos=LinLin.kr(phase,startsamp,stopsamp,0,1);

      SendReply.kr(send_sig_pos*Impulse.kr(15),"/sc_fcm2dcorpus/transport_sig_pos",transport_sig_pos.value);

      // Out.ar(out,transportSig);

      Out.ar([0,1],[transportSig*0.5,transportSig*0.5]*tamp*gate*(1-record_buf));
    }).add;

    s.sync;

    eglut=EGlut.new(s,context,this);
    "eglut inited".postln;
    
     //play a slice
    play_slice={
      arg index,startInt,durInt,volume=1;
      {
        var startsamp=Index.kr(indices,index);
        var stopsamp=Index.kr(indices,index+1);
        var phase=Phasor.ar(0,BufRateScale.kr(src),startsamp,stopsamp);
        var sig=BufRd.ar(1,src,phase)*volume*2;
        var env;
        var dursecs=(stopsamp - startsamp) / BufSampleRate.kr(src);
        var ptr,bufrate;

        dursecs=min(dursecs,1);
        env=EnvGen.kr(Env([0,1,1,0],[0.05,dursecs-0.1,0.05]),doneAction:2);
        // ["compose gslicebuf",s,src,gslicebuf].postln;
        Routine({          
          FluidBufCompose.process(s,src,startInt,durInt,startChan: 0,numChans: 1,gain: 1,destination: gslicebuf,destStartFrame: 0,destStartChan: 0,destGain: 0,freeWhenDone: true,action: {
            // ["gslicebuf composed",gslicebuf].postln;
            lua_sender.sendMsg("/lua_fcm2dcorpus/gslicebuf_composed");
          });
        }).play;


        //
        

        sig.dup * env * 0.125;
      }.play;
    };
    
    compose={
      arg folder_path,file_path,max_sample_length=1.5;
      var loader;
      var src_path=audio_path ++ "temp/src" ++ compose_buffer_recorder_ix ++ ".wav";
      var header_format="WAV";
      var sample_format="int24";
      var sample_length;
      src=Buffer.new(s);
      
      fork{
        "start loader".postln;
        if (folder_path != nil,{
          "load folder".postln;
          folder_path.postln;
          loader=FluidLoadFolder(folder_path).play(s);
          sample_length=loader.buffer.numFrames/loader.buffer.sampleRate/60;
          sample_length=min(sample_length,max_sample_length);
          ["sample_length",sample_length].postln;
          // s.sync;
            ["loader loaded",].postln;
            "set mono src buffer".postln;
            if(loader.buffer.numChannels > 1){
              "stereo to mono".postln;
              // src=Buffer.new(s);
              FluidBufCompose.processBlocking(s,loader.buffer,numFrames: loader.buffer.sampleRate*60*sample_length,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                ("buf composed1").postln;
                FluidBufCompose.processBlocking(s,loader.buffer,numFrames: loader.buffer.sampleRate*60*sample_length,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                  ("buf composed2").postln;
                  ["audio composition completed",loader.buffer,loader.buffer.sampleRate*60*sample_length,src,src_path,header_format,sample_format].postln;
                  writebuf.(src,src_path,header_format,sample_format,-1,"compose");
                  compose_buffer_recorder_ix=(compose_buffer_recorder_ix+1).wrap(0,10);
                });
              });
            }{
              "audio is already mono".postln;
              ["audio composition completed",loader.buffer,loader.buffer.sampleRate*60*2,src,src_path,header_format,sample_format].postln;
              writebuf.(src,src_path,header_format,sample_format,-1,"compose");
              compose_buffer_recorder_ix=compose_buffer_recorder_ix+1;
              compose_buffer_recorder_ix.wrap(0,10);
            };
        },{
          ["load file",folder_path,file_path].postln;
          loader=Buffer.read(s,file_path);
          // src=Buffer.new(s);
          s.sync;
          sample_length=loader.numFrames/loader.sampleRate/60;
          sample_length=min(sample_length,max_sample_length);
          ["sample_length",sample_length].postln;
          ["file loaded",loader.numChannels,loader.numFrames,loader.sampleRate].postln;
          if(loader.numChannels > 1){
            "stereo to mono".postln;
            FluidBufCompose.process(s,loader,numFrames: loader.sampleRate*60*sample_length,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
              ("buf composed1").postln;
              FluidBufCompose.process(s,loader,numFrames: loader.sampleRate*60*sample_length,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                ("buf composed2").postln;
                ["audio composition completed",src.numChannels,src.numFrames,src.sampleRate].postln;
                writebuf.(src,src_path,header_format,sample_format,-1,"compose");
              });
            });
          }{
            "audio is already mono".postln;
            src=loader;
            writebuf.(src,src_path,header_format,sample_format,-1,"compose");
            ["mono audio composition completed",src.numChannels,src.numFrames,src.sampleRate].postln;
          };
          // });
        });
      };
    };

    dump_normed={
      arg normed;
      tree=FluidKDTree(s,numNeighbours:1,radius:0.5).fit(normed);
      s.sync;
      ["tree set",tree].postln;
      ["start normed dump",normed,findices].postln;
      ////////////////////////
      normed.dump({ arg dict;
        var dictkeys;
        var deststarts=List.new();
        var durations=List.new();
        normedBuf=Buffer(s);
        //save the buffer in the order of the dict
        dictkeys=dict.values[0].keys;
        dictkeys=Array.newFrom(dictkeys);
        ["indices.loadToFloatArray",dictkeys].postln;
        findices.doAdjacentPairs{
          arg start,end,i;
          var num=end - start;
          durations.add(num);
        };
        "durations captured".postln;
        dictkeys.do({|key,i|
          var startsamp,stopsamp,start_frame,duration,dest_start,lastduration=0,laststart;
          start_frame=findices[key.asInteger];
          duration=durations[key.asInteger];
          if(i>0,{
            lastduration=durations[dictkeys[i-1].asInteger];
            laststart=deststarts[i-1];
            dest_start=lastduration+laststart;
            deststarts.insert(i,dest_start);
            FluidBufCompose.process(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:dest_start,action:{});
          },{
            deststarts.insert(i,0);
            FluidBufCompose.process(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:0,action:{});
          });
        });
      },action:{

      });
      normed.write(Platform.defaultTempDir+/+"temp_dataset.json");
      "normed json file generated".postln;
      [Platform.defaultTempDir+/+"temp_dataset.json"].postln;
      
      lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_dumped");

    };
    analyze={
      arg sliceThresh=0.5,min_slice_length=2;
      var analyses,normed,umapped2d,normed_dict;
  
      indices=Buffer.new(s);
      point=Buffer.alloc(s,2);
      fork{
        ["slice and analyze",indices].postln;
        // 0.5.wait;
        "start FluidBufOnsetSlice".postln;
        FluidBufOnsetSlice.process(s,src,numChans: 1,metric:0,threshold:sliceThresh,minSliceLength:min_slice_length,indices:indices,windowSize:1024,hopSize: 1024*2,action:{
          "FluidBufOnsetSlice done".postln;
          "average seconds per slice: %".format(src.duration / indices.numFrames).postln;
          analyses=FluidDataSet(s).clear;
          ["start analysis"].postln;
          indices.loadToFloatArray(action:{
            arg fa;
            var umap_iterations=10;
            var umap_num_neighbors=15;
            var mfccs=Buffer(s);
            var stats=Buffer(s);
            var flat=Buffer(s);
            findices=fa;
            fa.doAdjacentPairs{
              arg start,end,i;
              var num=end - start;

              FluidBufMFCC.process(s,src,start,num,features:mfccs,numCoeffs:13,startCoeff:1,action:{
                FluidBufStats.process(s,mfccs,stats:stats,select:[\mean],action:{
                  FluidBufFlatten.process(s,stats,destination:flat,action:{
                    analyses.addPoint(i,flat);
                    if(i+1==fa.size,{
                      lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_progress",i,fa.size);
                    });
                    if(i%10==9,{
                      "slice % / %".format(i,fa.size).postln;
                      lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_progress",i,fa.size);
                    });

                  });
                });
              });
              s.sync;
            };

            s.sync;
            (">>>>>>>>>>>>analyses done<<<<<<<<<<<").postln;
            analyses.postln;
            "create umapped2d".postln;
            umapped2d=FluidDataSet(s);
            if (fa.size < umap_num_neighbors,{ umap_num_neighbors=(fa.size/2).floor });
            FluidUMAP(s,numDimensions:2,numNeighbours:umap_num_neighbors,minDist:0.9,iterations:umap_iterations).fitTransform(analyses,umapped2d);
            "umap done".postln;
            normed=FluidDataSet(s);
            FluidNormalize(s).fitTransform(umapped2d,normed);

            ["umap normed",normed].postln;
            ["analysis all done"].postln;
            dump_normed.(normed);
            /////////////////////////
          });
        });
      };
    };

    osc_funcs.put("set_2dcorpus",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var path=msg[1].asString;
        var path_type=msg[2].asString;
        var max_sample_length=msg[3].asInteger;
        // var folder_path="/home/we/dust/code/fcm2dcorpus/lib/audio/";
        if (path_type=="folder",{
          (["call compose: folder",path,max_sample_length]).postln;
          compose.(path,nil,max_sample_length);
        },{
          (["call compose: file",path,max_sample_length]).postln;
          compose.(nil,path,max_sample_length);
        });
      },"/sc_fcm2dcorpus/set_2dcorpus");
    );

    osc_funcs.put("write_normed",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var path=msg[1];
        var header_format="WAV";
        var sample_format="int24";
        ["write normed",normedBuf,path,header_format,sample_format].postln;
        writebuf.(normedBuf,path,header_format,sample_format,-1,"analyze");
      },"/sc_fcm2dcorpus/write_normed");
    );

    osc_funcs.put("analyze_2dcorpus",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var threshold=msg[1];
        var min_slice_length=msg[2];
        analyze.(threshold,min_slice_length);
      },"/sc_fcm2dcorpus/analyze_2dcorpus");
    );

    osc_funcs.put("record_live",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var dur=msg[1];
        var path=audio_path ++ "temp/live.wav";
        var header_format="WAV";
        var sample_format="int24";
        players.keysValuesDo({ arg k,val;
          val.set(\gate,0);
        });

        src=Buffer.alloc(s,s.sampleRate * dur,1);

        recorders.add(\live_recorder ->
          Synth(\live_recorder,[\dur,dur,\buf,src]);
        );

        
        Routine{
          dur.wait;
          ["recording completed",src].postln;
          writebuf.(src,path,header_format,sample_format,-1,"composelive");
        }.play;

      },"/sc_fcm2dcorpus/record_live");
    );

    osc_funcs.put("play_slice",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var x=msg[1];
        var y=msg[2];
        var volume=msg[3];
        point.setn(0,[x,y]);
        tree.kNearest(point,1,{
          arg nearest_slice;
          var start,stop,dur;
          if(nearest_slice != current_slice){
            start=findices[nearest_slice.asInteger];
            stop=findices[nearest_slice.asInteger+1];
            dur=stop-start;
            // ["start,dur",start,stop,dur].postln;
            play_slice.(nearest_slice.asInteger,start,dur,volume);
            current_slice=nearest_slice;
            lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",current_slice,previous_slice);
            // previous_slice=nearest_slice;
          }
        });
      },"/sc_fcm2dcorpus/play_slice");
    );
    
    osc_funcs.put("append_gslice",
      OSCFunc.new({ |msg,time,addr,recvPort|
        append_gslice.();
      },"/sc_fcm2dcorpus/append_gslice");
    );
    
    osc_funcs.put("remove_selected_gslice",
      OSCFunc.new({ |msg,time,addr,recvPort|
        ["remove_selected_gslice",msg].postln;
        remove_gslice.(bufnum:msg[1],slice_num:msg[2].asInteger,start_frame:msg[3].asInteger,end_frame:msg[4].asInteger);
      },"/sc_fcm2dcorpus/remove_selected_gslice");
    );

    osc_funcs.put("get_gslices",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var slice_points="";
        gslices.do({arg slice,i; 
          slice.do({arg slice_point,j;
            if((i==0).and(j==0),{
              slice_points=slice_point.asString;
            },{
              slice_points=slice_points ++ "," ++ slice_point.asString;
            }) 
          });
          ["slice_points",slice_points].postln;
          lua_sender.sendMsg("/lua_fcm2dcorpus/set_gslices",slice_points);
        });
      },"/sc_fcm2dcorpus/get_gslices");
    );


    osc_funcs.put("transport_slices",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var x=msg[1];
        var y=msg[2];
        
        point.setn(0,[x,y]);
        //note: error may be related to: https://discourse.flucoma.org/t/kNearest-issue/1508/5
        tree.kNearest(point,1,action:{
          arg nearest_slice;
          var startsamp,stopsamp;
    			var startsamp_prev,stopsamp_prev;
          var transportNumFrames,transportDuration;
          
          if(playing_slice==false,{
              players.at(\transport_player).set(\gate,0);
              players.at(\transport_player).free;
              recorders.at(\transport_recorder).set(\gate,0);
              recorders.at(\transport_recorder).free;
              // s.sync;
              // ["dump node tree",s.queryAllNodes].postln;

            Routine({

              if (recorders.at(\transport_recorder)!=nil,{
              });
            });

            Routine({
              s.sync;
              if (players.at(\transport_player)!=nil,{
                ["transport slice nearest_slice/current_slice1",nearest_slice,current_slice,previous_slice].postln;
                startsamp_prev=findices[previous_slice.asInteger];
                stopsamp_prev=findices[previous_slice.asInteger+1];
                
              },{
                if(previous_slice!=nil,{
                  ["transport slice nearest_slice/current_slice2",nearest_slice,current_slice,previous_slice].postln;
                  startsamp_prev=findices[previous_slice.asInteger];
                  stopsamp_prev=findices[previous_slice.asInteger+1];
                },{
                startsamp_prev=findices[nearest_slice.asInteger];
                stopsamp_prev=findices[nearest_slice.asInteger+1];
                });
              });
              startsamp=findices[nearest_slice.asInteger];
              stopsamp=findices[nearest_slice.asInteger+1];
              players.add(\transport_player->Synth(
                \transport_synth,[
                  // \out,buses.at(\busTransport),
                  \xloc,x,
                  \yloc,y,
                  \src,src,
                  \index,nearest_slice.asInteger,
                  \startsamp,startsamp,
                  \stopsamp,stopsamp,
                  \startsamp_prev,startsamp_prev,
                  \stopsamp_prev,stopsamp_prev,
                  \tamp,transport_volume,
                  \transport_rate,0,
                  \transport_buffer,transport_buffer_dummy,
                  \transport_trig_rate,transport_trig_rate,
                  \stretch,transport_stretch,
                  \reset_pos,transport_reset_pos,
                  \send_sig_pos,1,
                  \record_buf,0
                ]
              ));

              recorders.add(\transport_recorder->Synth(
                \transport_synth,[
                  // \out,buses.at(\busTransport),
                  \xloc,x,
                  \yloc,y,
                  \src,src,
                  \index,nearest_slice.asInteger,
                  \startsamp,startsamp,
                  \stopsamp,stopsamp,
                  \startsamp_prev,startsamp_prev,
                  \stopsamp_prev,stopsamp_prev,
                  \tamp,transport_volume,
                  \transport_rate,0,
                  \transport_buffer,transport_buffer_dummy,
                  \transport_trig_rate,transport_trig_rate,
                  \stretch,transport_stretch,
                  \reset_pos,transport_reset_pos,
                  \send_sig_pos,0,
                  \record_buf,0
                ]
              ));

              // transportBuffers[transport_buffer_next].zero;

              // players.add(\transport_bus_player->Synth(
              //   \transport_bus_player,[
              //     \in, buses.at(\busTransport)
              //   ];
              // ));

              lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",nearest_slice,current_slice);
              // current_slice=nearest_slice;
              previous_slice=current_slice;

              ["record transport sig",srcSampleRate].postln;
              players.at(\transport_player).set(\transport_rate,transport_rate);
              recorders.at(\transport_recorder).set(\transport_rate,transport_rate);
              sc_sender.sendMsg("/sc_fcm2dcorpus/record_transportbuf")
            }).play;
          });
        });
      },"/sc_fcm2dcorpus/transport_slices");
    );

    osc_funcs.put("transport_gate",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var gate=msg[1];
        ["transport_gate",gate].postln;
        players.at(\transport_player).set(\gate,gate);
        recorders.at(\transport_recorder).set(\gate,gate);
      },"/sc_fcm2dcorpus/transport_gate");
    );
    
    osc_funcs.put("set_transport_volume",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var vol=msg[1];
        transport_volume=vol;
        players.at(\transport_player).set(\tamp,vol);
        recorders.at(\transport_recorder).set(\tamp,vol);
      },"/sc_fcm2dcorpus/set_transport_volume");
    );


    osc_funcs.put("transport_x_y",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var x=msg[1];
        var y=msg[2];
        players.at(\transport_player).set(\yloc,y);
        players.at(\transport_player).set(\xloc,x);
        recorders.at(\transport_recorder).set(\xloc,x);
        recorders.at(\transport_recorder).set(\yloc,y);
        sc_sender.sendMsg("/sc_fcm2dcorpus/record_transportbuf")
      },"/sc_fcm2dcorpus/transport_x_y");
    );

    osc_funcs.put("set_transport_rate",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var rate=msg[1];
        transport_rate=rate;
        players.at(\transport_player).set(\transport_rate,rate);
      },"/sc_fcm2dcorpus/set_transport_rate");
    );

    osc_funcs.put("set_transport_trig_rate",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var rate=msg[1];
        transport_trig_rate=rate;
        players.at(\transport_player).set(\transport_trig_rate,rate);
      },"/sc_fcm2dcorpus/set_transport_trig_rate");
    );

    osc_funcs.put("set_transport_reset_pos",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var pos=msg[1];
        transport_reset_pos=pos;
        players.at(\transport_player).set(\reset_pos,pos);
        // sc_sender.sendMsg("/sc_fcm2dcorpus/record_transportbuf")
      },"/sc_fcm2dcorpus/set_transport_reset_pos");
    );

    osc_funcs.put("set_transport_stretch",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var stretch=msg[1];
        transport_stretch=stretch;
        players.at(\transport_player).set(\stretch,stretch);
        recorders.at(\transport_recorder).set(\stretch,stretch);
        sc_sender.sendMsg("/sc_fcm2dcorpus/record_transportbuf")
      },"/sc_fcm2dcorpus/set_transport_stretch");
    );

    osc_funcs.put("record_transportbuf",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var startsamp=findices[current_slice.asInteger];
        var stopsamp=findices[current_slice.asInteger+1];
        var startsamp_prev=findices[previous_slice.asInteger];
        var stopsamp_prev=findices[previous_slice.asInteger+1];
        var transportNumFrames,transportNumFrames_prev,transportDuration;
        var transport_buffer;
        transportNumFrames=(((stopsamp+(transport_stretch*server_sample_rate))-startsamp)).ceil;
          transportDuration=(transportNumFrames/server_sample_rate);
          transportDuration=max(transportDuration,3);
          ["buf_writing,buf_recording",buf_writing,buf_recording].postln;
          Tdef(\record_transportbuf_timer,{ 
            ["buf_recording",buf_recording].postln;
            ["start record transport buffer ",
              // transportBuffer,
              transportDuration,
              transportNumFrames
            ].postln;

            transport_buffer_current=transport_buffer_next;
            transport_buffer=transportBuffers[transport_buffer_current];
            ["transport_buffer,transport_buffer_current",transport_buffer,transport_buffer_current].postln;
            recorders.at(\transport_recorder).set(\transport_buffer,transport_buffer);


            buf_recording=1;
            recorders.at(\transport_recorder).set(\record_buf,1);
            recorders.at(\transport_recorder).set(\transport_trig_rate,transportDuration.reciprocal);

            if (transport_buffer_next < 9,{
              transport_buffer_next=transport_buffer_next+1;
            },{
              transport_buffer_next=0;
            });
            transportBuffers[transport_buffer_next].zero;

            transportDuration.wait;
            if(transportNumFrames!=nil,{
              sc_sender.sendMsg("/sc_fcm2dcorpus/on_transport_buffer_recorded",transportNumFrames);
            });
            buf_recording=0;
            recorders.at(\transport_recorder).set(\record_buf,0);
            ["done recording",Tdef(\record_transportbuf_timer)].postln;
            Tdef(\record_transportbuf_timer).stop;
            // Tdef(\record_transportbuf_timer).clear;
          }).play;
      },"/sc_fcm2dcorpus/record_transportbuf");
    );

    osc_funcs.put("on_transport_buffer_recorded",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var path=audio_path ++ "temp/transport" ++ transport_buffer_recorder_ix ++ ".wav";
        var num_frames=msg[1];
        var header_format="WAV";
        var sample_format="int24";
        ["transport buffer recorded",num_frames].postln;
        if(num_frames!=nil,{
          var transport_buffer=transportBuffers[transport_buffer_current];
          writebuf.(transport_buffer,path,header_format,sample_format,num_frames,"transport");
        },{
          "don't write! num_frames NIL!"
        });     
        transport_buffer_recorder_ix=(transport_buffer_recorder_ix+1).wrap(0,3);
        // pos.postln;
        // lua_sender.sendMsg("/lua_fcm2dcorpus/transport_sig_pos",pos);
      },"/sc_fcm2dcorpus/on_transport_buffer_recorded");
    );

    osc_funcs.put("transport_sig_pos",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var pos=msg[3];
        // pos.postln;
        lua_sender.sendMsg("/lua_fcm2dcorpus/transport_sig_pos",pos);
      },"/sc_fcm2dcorpus/transport_sig_pos");
    );

    osc_funcs.put("init_completed",
      OSCFunc.new({ |msg,time,addr,recvPort|
        recorders.add(\live_streamer ->
          Synth(\live_streamer, [\buf,live_buffer]);
        );
      },"/sc_fcm2dcorpus/init_completed");
    );   

    osc_funcs.put("write_live_streamer",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var path=audio_path ++ "temp/live_buffer.wav";
        var num_frames=msg[3];
        var header_format="WAV";
        var sample_format="int24";
        if(num_frames!=nil,{
          writebuf.(live_buffer,path,header_format,sample_format,num_frames,"live");
        },{
          "don't write! num_frames NIL!"
        });     
      },"/sc_fcm2dcorpus/write_live_streamer");
    );

    osc_funcs.put("write_live_stream_enabled",
      OSCFunc.new({ |msg,time,addr,recvPort|
        ["write_live_stream_enabled",msg[1]].postln;
        recorders.at(\live_streamer).set(\write_live_stream_enabled,msg[1]);
      },"/sc_fcm2dcorpus/write_live_stream_enabled");
    );   

    osc_funcs.put("granulate_live",
      OSCFunc.new({ |msg,time,addr,recvPort|
        var voice=msg[1];
        live_buffer = Buffer.alloc(s, s.sampleRate * 3,completionMessage:{
          ["gran_live",voice,live_buffer].postln;
          Synth(\live_streamer, [\buf,live_buffer]);
          eglut.readBuf(voice,live_buffer,0);
        });
      },"/sc_fcm2dcorpus/granulate_live");
    );   

    osc_funcs.put("live_audio_dry_wet",
      OSCFunc.new({ |msg,time,addr,recvPort|
        msg[1].postln;
        recorders.at(\live_streamer).set(\dry_wet,msg[1]);
      },"/sc_fcm2dcorpus/live_audio_dry_wet");
    );     
    "engine all loaded".postln;   
  }

  free {
    "free FCM2dCorpus".postln;  
    buses.keysValuesDo({ arg k,val;
      val.free;
    });
    osc_funcs.keysValuesDo({ arg k,val;
      val.free;
    });
    recorders.keysValuesDo({ arg k,val;
      val.free;
    });
    players.keysValuesDo({ arg k,val;
      val.free;
    });
    transportBuffers.keysValuesDo({ arg k,val;
      val.free;
    });
    gslices.free;
    eglut.free();
  }
}
