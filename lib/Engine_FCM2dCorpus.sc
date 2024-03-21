// Engine_FCM2dCorpus

// Inherit methods from CroneEngine
Engine_FCM2dCorpus : CroneEngine {
  var s;
  var twoD_instrument,lua_sender,sc_sender;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

  alloc {                      
    s = context.server;
    s.options.memSize  = 8192/2; 
    lua_sender = NetAddr.new("127.0.0.1",10111);   
    sc_sender = NetAddr.new("127.0.0.1",57120);   
    lua_sender.sendMsg("/lua_fcm2dcorpus/sc_inited");

    //init
    OSCFunc.new({ |msg, time, addr, recvPort|
      var folder_path="/home/we/dust/code/fcm2dcorpus/lib/audio/";
      "init".postln;
      twoD_instrument.(folder_path,nil);
    }, "/sc_fcm2dcorpus/init");

    //2d corpus instrument
    Routine.new({
      twoD_instrument = {
        arg folder_path, file_path, sliceThresh = 0.1;
        var previous;
        var analyses, normed, umapped, normed_dict, tree;
        var src, play_slice;
        var indices = Buffer(s);
        var loader;
        var point = Buffer.alloc(s,2);

        fork{

          "start loader".postln;
          if (folder_path != nil,{
            folder_path.postln;
            loader = FluidLoadFolder(folder_path).play(s,{
              "loader loaded".postln;
              "set mono src buffer".postln;
              if(loader.buffer.numChannels > 1){
                "stereo to mono".postln;
                src = Buffer(s);
                FluidBufCompose.processBlocking(s,loader.buffer,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                  ("buf composed1").postln;
                  FluidBufCompose.processBlocking(s,loader.buffer,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                    ("buf composed2").postln;
                  });
                });
              }{
                "audio is already mono".postln;
                src = loader.buffer
              };
              "sliced".postln;
            });
          },{
            src = Buffer.read(s,file_path);
            s.sync;
            if(src.numChannels > 1){
              "stereo to mono".postln;
              FluidBufCompose.processBlocking(s,src,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                ("buf composed1").postln;
                FluidBufCompose.processBlocking(s,src,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                  ("buf composed2").postln;
                });
              });
            }
          });
          s.sync;
          src.postln;
          FluidBufOnsetSlice.process(s,src,metric:9,threshold:sliceThresh,indices:indices,action:{
            "FluidBufOnsetSlice done".postln;
            "average seconds per slice: %".format(src.duration / indices.numFrames).postln;

            // analysis
            "start analysis".postln;
            analyses = FluidDataSet(s);
            indices.loadToFloatArray(action:{
              arg fa;
              var mfccs = Buffer(s);
              var stats = Buffer(s);
              var flat = Buffer(s);

              fa.doAdjacentPairs{
                arg start, end, i;
                var num = end - start;

                FluidBufMFCC.processBlocking(s,src,start,num,features:mfccs,numCoeffs:13,startCoeff:1,action:{});
                s.sync;
                FluidBufStats.processBlocking(s,mfccs,stats:stats,select:[\mean],action:{});
                s.sync;
                FluidBufFlatten.processBlocking(s,stats,destination:flat,action:{});

                analyses.addPoint(i,flat);
                if((i%3) ==2){
                  "slice % / %".format(i,fa.size).postln;
                  s.sync;
                };
                s.sync;
              };

              s.sync;
              (">>>>>>>>>>>>analyses done<<<<<<<<<<<").postln;

              analyses.postln;

              umapped = FluidDataSet(s);
              
              FluidUMAP(s,numDimensions:2,numNeighbours:15,minDist:0.9,iterations:100).fitTransform(analyses,umapped,action:{
                "umap done".postln;
                umapped.print;
                normed = FluidDataSet(s);
                FluidNormalize(s).fitTransform(umapped,normed);

                "normed".postln;
                normed.postln;

                tree = FluidKDTree(s, numNeighbours:1, radius:0.5).fit(normed);

                normed.write(Platform.defaultTempDir+/+"temp_dataset.json");
                "normed json file generated".postln;
                [Platform.defaultTempDir+/+"temp_dataset.json"].postln;
                Routine({                      
                  1.wait;
                }).play;
                // normed_dict.postln;

                lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_dumped");
              });
            });
          });
        };

        //play a slice
        play_slice = {
          arg index;
          {
            var startsamp = Index.kr(indices,index);
            var stopsamp = Index.kr(indices,index+1);
            var phs = Phasor.ar(0,BufRateScale.ir(src),startsamp,stopsamp);
            var sig = BufRd.ar(1,src,phs);
            var dursecs = (stopsamp - startsamp) / BufSampleRate.ir(src);
            var env;
            dursecs = min(dursecs,1);
            env = EnvGen.kr(Env([0,1,1,0],[0.03,dursecs-0.06,0.03]),doneAction:2);
            sig.dup * env;
          }.play;
        };

        OSCFunc.new({ |msg, time, addr, recvPort|
          var x=msg[1];
          var y=msg[2];
          point.setn(0,[x,y]);
          tree.kNearest(point,1,{
            arg nearest;
            if(nearest != previous){
              [x,y,nearest].postln;
              play_slice.(nearest.asInteger);
              lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",nearest);
              previous = nearest;
            }
          });
        }, "/sc_fcm2dcorpus/play_slice");
      };
    }).play;
  }

  free {
    "free FCM2dCorpus".postln;    
    twoD_instrument.free;
  }
}
