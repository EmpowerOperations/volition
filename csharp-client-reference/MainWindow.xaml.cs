using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Forms;
using EmpowerOps.Volition.DTO;
using Google.Protobuf.Collections;
using Grpc.Core;
using static EmpowerOps.Volition.DTO.NodeStatusCommandOrResponseDTO.Types;
using Application = System.Windows.Application;
using MessageBox = System.Windows.MessageBox;

namespace EmpowerOps.Volition.RefClient
{
    /// <summary>
    /// Interaction logic for MainWindow.xaml
    /// </summary>
    public partial class MainWindow : Window
    {
        private readonly Optimizer.OptimizerClient _client;
        private readonly Channel _channel;
        private AsyncServerStreamingCall<OASISQueryDTO> _requests;
        private ChannelState channelState;
        private string _name;    
        private bool _isRegistered = false;
        private bool _isCanceled = false;
        private bool _failToggle = false;
        private readonly BindingSource _inputSource = new BindingSource();
        private readonly BindingSource _outputSource = new BindingSource();
        private CancellationTokenSource _evaluationCancellationTokenSource;

        public MainWindow()
        {
            try
            {
                
//                KeyCertificatePair x = new KeyCertificatePair();
                
                //https://grpc.io/docs/quickstart/csharp.html#update-the-client
//                var certText = File.ReadAllText("C:/Users/Geoff/Code/volition/sslcerts/ca.crt");
//                var selfSignedCert = new SslCredentials(certText);
                var selfSignedCert = ChannelCredentials.Insecure;

                _channel = new Channel("127.0.0.1", 5550, selfSignedCert);
                _client = new Optimizer.OptimizerClient(_channel);
                InitializeComponent();
                
                UpdateConnectionStatusAsync();
                UpdateButton();
                ConfigGrid();
            }
            catch (Exception ex)
            {
                Debug.WriteLine(ex.ToString());
            }
        }

        private void ConfigGrid()
        {
            InputGrid.ItemsSource = _inputSource;
            OutputGrid.ItemsSource = _outputSource;
        }

        private void StartOptimization_Click(object sender, RoutedEventArgs e)
        {
            _client.startOptimization(new StartOptimizationCommandDTO());
            Log($"Server: Started Received");
            UpdateButton();
        }

        private void StopOptimization_Click(object sender, RoutedEventArgs e)
        {
            _client.StopOptimization(new StopOptimizationCommandDTO());
            Log($"Server: Stopped Received");
            UpdateButton();
        }

        private async void UpdateConnectionStatusAsync()
        {
            channelState = _channel.State;
            ConnectionStatus.Text = $"Connection: {channelState.ToString()}";
            while (true)
            {
                await _channel.WaitForStateChangedAsync(channelState);
                channelState = _channel.State;
                ConnectionStatus.Text = $"Connection: {channelState.ToString()}";
            }

        }
        
        private void UpdateButton()
        {
            RegistrationStatus.Text = _isRegistered ? $"Registered as {_name}" : $"Not registered"; 
        }

        private async Task HandlingRequestsAsync(AsyncServerStreamingCall<OASISQueryDTO> requests)
        {
            //request for inputs, do the simulation, return result, repeat
            var requestsResponseStream = requests.ResponseStream;
            try
            {
                while (await requestsResponseStream.MoveNext(new CancellationToken()))
                {
                    HandleRequestAsync(requestsResponseStream.Current);
                }

                Log("- Query Closed, plugin has been unregisred by server");
            }
            catch (Exception e)
            {
                Log($"- Error happened when reading from request stream, unregistered\n{e}");
            }
            finally
            {
                _name = "";
                _requests = null;
                _isRegistered = false;
                UpdateButton();
            }
           
        }
       
        private async Task HandleRequestAsync(OASISQueryDTO request)
        {
            try
            {
                switch (request.RequestCase)
                {
                    case OASISQueryDTO.RequestOneofCase.EvaluationRequest:
                    {
                        Log($"Server: Request Evaluation");
                        await Evaluate(request);
                        break;
                    }
                    case OASISQueryDTO.RequestOneofCase.NodeStatusRequest:
                        Log($"Server: Request Node Status");
                        _client.offerSimulationConfig(BuildNodeUpdateResponse());
                        break;
                    case OASISQueryDTO.RequestOneofCase.CancelRequest:
                        Log($"Server: Request Cancel");
                        _evaluationCancellationTokenSource.Cancel();
                        break;
                    case OASISQueryDTO.RequestOneofCase.None:
                        break;
                }
            }
            catch (Exception e)
            {
                _client.offerErrorResult(new ErrorResponseDTO
                {
                    Name = _name,
                    Message = $"Error handling request [{request.RequestCase}]",
                    Exception = e.ToString()
                });
            }
        }

        private async Task Evaluate(OASISQueryDTO request)
        {
            MapField<string, double> inputs = request.EvaluationRequest.InputVector;
            Log($"- Requested Input: [{inputs}]");

            foreach (Input input in _inputSource)
            {
                input.EvaluatingValue = inputs[input.Name];
            }
            foreach (Output output in _outputSource)
            {
                output.EvaluatingValue = "Evaluating...";
            }
            UpdateBindingSourceOnUI(_inputSource);
            UpdateBindingSourceOnUI(_outputSource);

            Log("- Evaluating...");
            _evaluationCancellationTokenSource = new CancellationTokenSource();
            var result = await SimulationEvaluation(inputs, _outputSource.List);

            foreach (Input input in _inputSource)
            {
                input.CurrentValue = inputs[input.Name];
            }

            foreach (Output output in _outputSource)
            {
                if (result.Output.ContainsKey(output.Name))
                {
                    output.CurrentValue = result.Output[output.Name].ToString();
                    output.EvaluatingValue = result.Output[output.Name].ToString();
                }
                else
                {
                    output.CurrentValue = result.Status.ToString();
                    output.EvaluatingValue = result.Status.ToString();
                }
            }

            UpdateBindingSourceOnUI(_inputSource);
            UpdateBindingSourceOnUI(_outputSource);
           
            switch (result.Status)
            {
                case EvaluationResult.ResultStatus.Succeed:
                    Log($"- Evaluation Succeed [{result.Output}]");
                    _client.offerSimulationResult(new SimulationResponseDTO
                    {
                        Name = _name,
                        OutputVector = {result.Output}
                    });
                    break;
                case EvaluationResult.ResultStatus.Failed:
                    Log($"- Evaluation Failed [{result.Output}]\nException: {result.Exception}");
                    _client.offerErrorResult(new ErrorResponseDTO
                    {
                        Name = _name,
                        Message = $"- Evaluation Failed when evaluating [{inputs}]",
                        Exception = result.Exception.ToString()
                    });
                    break;
                case EvaluationResult.ResultStatus.Canceled:
                    Log($"- Evaluation Canceled");
                    _client.offerSimulationResult(new SimulationResponseDTO
                    {
                        Name = _name,
                        OutputVector = {result.Output}
                    });
                    break;
                default:
                    throw new ArgumentOutOfRangeException();
            }
        }

        private Task<EvaluationResult> SimulationEvaluation(MapField<string, double> inputs, IList outputs)
        {
            return Task.Run(() =>
            {
                try
                {
                    Thread.Sleep(2000);
                    _evaluationCancellationTokenSource.Token.ThrowIfCancellationRequested();
                    if (_failToggle)
                    {
                        _failToggle = false;
                        throw new EvaluationException($"Error evaluating {inputs}");
                    }

                    Thread.Sleep(2000);
                    _evaluationCancellationTokenSource.Token.ThrowIfCancellationRequested();

                    var result = new MapField<string, double>();
                    var random = new Random();

                    foreach (Output output in outputs)
                    {
                        var evaluationResult = random.NextDouble();
                        result.Add(output.Name, evaluationResult);
                    }
                 
                    return new EvaluationResult { Input = inputs, Output = result, Status = EvaluationResult.ResultStatus.Succeed };
                }
                catch (OperationCanceledException)
                { 
                    return new EvaluationResult { Input = inputs, Output = new MapField<string, double>(), Status = EvaluationResult.ResultStatus.Canceled };
                }
                catch (EvaluationException e)
                {
                    var result = new MapField<string, double>();
                    foreach (Output output in outputs)
                    {
                        var evaluationResult = Double.PositiveInfinity;
                        result.Add(output.Name, evaluationResult);
                    }

                    return new EvaluationResult
                    {
                        Input = inputs,
                        Output = result,
                        Status = EvaluationResult.ResultStatus.Failed,
                        Exception = e
                    };
                }
                catch (Exception e)
                {
                    return new EvaluationResult { Input = inputs,
                        Output = new MapField<string, double>(),
                        Status = EvaluationResult.ResultStatus.Failed,
                        Exception = e
                    };
                }

            }, _evaluationCancellationTokenSource.Token);
        }

        private void UpdateNode()
        {
            _client.updateNode(BuildNodeUpdateResponse());
        }

        private NodeStatusCommandOrResponseDTO BuildNodeUpdateResponse()
        {
            var nodeStatusCommandOrResponseDto = new NodeStatusCommandOrResponseDTO
            {
                Name = _name,
                Description = "Volition Reference Client"
            };
            //gather inputs/ outputs NodeStatusCommandOrResponseDTO
            foreach (Input input in _inputSource)
            {
                nodeStatusCommandOrResponseDto.Inputs.Add(new PrototypeInputParameter
                {
                    Name = input.Name,
                    LowerBound = input.LowerBound,
                    UpperBound = input.UpperBound,
                });
            }

            foreach (Output output in _outputSource)
            {
                nodeStatusCommandOrResponseDto.Outputs.Add(new PrototypeOutputParameter
                {
                    Name = output.Name
                });
            }

            return nodeStatusCommandOrResponseDto;
        }

        private async void Register_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                //TODO better error state handing, when register failed due to no connection, it is not well though right now
                //TODO also when error flow when register e.g. same name
                var registrationCommandDto = new RegistrationCommandDTO {Name = RegName.Text};
                if (_requests != null)
                {
                    Log("- Node already registered");
                    return;
                }

                if (_channel.State != ChannelState.Ready)
                {
                    Log($"- Attempting to connect...");

                    var deadline = DateTime.UtcNow + TimeSpan.FromSeconds(20);
                    await _channel.ConnectAsync(deadline);
                }

                Log($"- Try Register as {RegName.Text}");
                _requests = _client.register(registrationCommandDto);
                if (_channel.State != ChannelState.Ready)
                {
                    await _channel.WaitForStateChangedAsync(_channel.State);
                }

                if (_channel.State == ChannelState.Ready)
                {
                    Log("- Registered");
                    _isRegistered = true;
                    _name = registrationCommandDto.Name;
                    UpdateButton();
                    HandlingRequestsAsync(_requests);
                }
                else
                {
                    Log("- Connection Failed, Not Registered");
                    _requests = null;
                    _isRegistered = false;
                    _name = null;
                    UpdateButton();
                }
            }
            catch (Exception ex)
            {
                Log($"Exception in registration call:\n{ex}");
                Debug.WriteLine(ex);
            }
        }

        private void Rename_Button_Click(object sender, RoutedEventArgs e)
        {
            var nodeNameChangeCommandDto = new NodeNameChangeCommandDTO {OldName = _name, NewName = RegName.Text };

            NodeNameChangeResponseDTO nodeNameChangeResponseDto = _client.changeNodeName(nodeNameChangeCommandDto);
            if (nodeNameChangeResponseDto.Changed)
            {
                MessageBox.Show($"Change name {nodeNameChangeCommandDto.OldName} to {nodeNameChangeCommandDto.NewName} succeed.");
                _name = nodeNameChangeCommandDto.NewName;
                RegisterLabel.Content = $"Registered as {_name}";
            }
            else
            {
                MessageBox.Show($"Change name {nodeNameChangeCommandDto.OldName} to {nodeNameChangeCommandDto.NewName} failed.");
            }
        }

        private void Log(string message)
        {
                  
            LogInfoTextBox.Text = LogInfoTextBox.Text += message + "\n";
            LogInfoTextBox.ScrollToEnd();
            if (_channel.State == ChannelState.Ready)
            { 
                _client.sendMessage(new MessageCommandDTO() { Name = _name ?? "no_name", Message = message });             
            }
           
        }

        private void UpdateBindingSourceOnUI(BindingSource bindingSource)
        {
            bindingSource.ResetBindings(false); 
        }

        private void AddInput_Button_Click(object sender, RoutedEventArgs e)
        {
            _inputSource.Add(new Input
            {
                Name = "x1", 
                LowerBound = 0.0,
                UpperBound = 10.0
            });
           
        }

        private void AddOutput_Button_Click(object sender, RoutedEventArgs e)
        {
            _outputSource.Add(new Output
            {
                Name = "f1"
            });
        }

        private void RemoveInput_Button_Click(object sender, RoutedEventArgs e)
        {
            _inputSource.Remove(InputGrid.SelectedItem);
        }

        private void RemoveOutput_Button_Click(object sender, RoutedEventArgs e)
        {
            _outputSource.Remove(OutputGrid.SelectedItem);
        }

        private void SyncButton_Click(object sender, RoutedEventArgs e)
        {
            UpdateNode();
        }

        private void UnRegister_Click(object sender, RoutedEventArgs e)
        {
            if (! _isRegistered)
            {
                MessageBox.Show($"Not yet registered");
                return ;
            }

            Unregister();
        }

        private void Unregister()
        {
            Log($"- Un-register {_name}");
            var responseDto = _client.unregister(new UnRegistrationRequestDTO() {Name = _name});
            var message = (responseDto.Unregistered ? "Successful" : "Failed");
            Log($"Server: Unregistered {message}");
            _name = "";
            _requests = null;
            _isRegistered = false;
            UpdateButton();
        }


        private void FailNextRun_Click(object sender, RoutedEventArgs e)
        {
            _failToggle = true;
        }
    }

    public class Input
    {
        public string Name { get; set; }
        public double LowerBound { get; set; }
        public double UpperBound { get; set; }
        public double CurrentValue { get; set; }
        public double EvaluatingValue { get; set; }
    }

    public class Output
    {
        public string Name { get; set; }
        public string CurrentValue { get; set; }
        public string EvaluatingValue { get; set; }
    }

    public class EvaluationException : Exception {
        public EvaluationException(string message) : base(message)
        {
        }
    }

    public class EvaluationResult
    {
        public enum ResultStatus
        {
            Succeed, Failed, Canceled
        }

        public ResultStatus Status { get; set; }
        public Exception Exception { get; set; }
       
        public MapField<string, double> Output { get; set; }
        public MapField<string, double> Input { get; set; }
    }


}
